"""LLM providers and task-based model routing.

* OllamaProvider talks to a local Ollama server (/api/chat, /api/embed, /api/tags).
  No quotas: the only limits are your hardware, context length and speed.
* OpenAICompatProvider is optional (FreeLLMAPI, OpenRouter, LM Studio, llama.cpp server, vLLM ...).

All providers stream `ChatChunk`s. There are no canned/fake responses anywhere: if no model
is reachable, the caller receives an `LLMError` with a human-readable fix.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

import httpx

from .config import Settings


class LLMError(RuntimeError):
    pass


@dataclass
class ChatChunk:
    text: str = ""
    thinking: str = ""
    tool_calls: list[dict] = field(default_factory=list)
    done: bool = False
    stats: dict = field(default_factory=dict)


@dataclass
class ModelInfo:
    name: str
    provider: str
    size: int = 0
    family: str = ""
    parameter_size: str = ""
    capabilities: list[str] = field(default_factory=list)


def _ollama_messages(messages: list[dict]) -> list[dict]:
    out = []
    for m in messages:
        mm = {"role": m["role"], "content": m.get("content") or ""}
        if m.get("images"):
            mm["images"] = m["images"]  # base64 strings
        if m.get("tool_calls"):
            mm["tool_calls"] = m["tool_calls"]
        if m.get("tool_name"):
            mm["tool_name"] = m["tool_name"]
        out.append(mm)
    return out


class OllamaProvider:
    name = "ollama"

    def __init__(self, base_url: str, num_ctx: int = 16384):
        self.base_url = base_url.rstrip("/")
        self.num_ctx = num_ctx

    def _client(self, timeout: float | None = None) -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=self.base_url, timeout=httpx.Timeout(timeout, connect=5.0), trust_env=False)

    async def health(self) -> dict:
        try:
            async with self._client(5) as c:
                r = await c.get("/api/version")
                r.raise_for_status()
                return {"ok": True, "version": r.json().get("version", "?")}
        except Exception as e:  # noqa: BLE001
            return {"ok": False, "error": f"Ollama not reachable at {self.base_url}: {e.__class__.__name__}"}

    async def list_models(self) -> list[ModelInfo]:
        async with self._client(10) as c:
            r = await c.get("/api/tags")
            r.raise_for_status()
            out = []
            for m in r.json().get("models", []):
                det = m.get("details") or {}
                out.append(ModelInfo(
                    name=m["name"], provider="ollama", size=m.get("size", 0), family=det.get("family", ""),
                    parameter_size=det.get("parameter_size", ""), capabilities=guess_capabilities(m["name"], det),
                ))
            return out

    async def show(self, model: str) -> dict:
        async with self._client(10) as c:
            r = await c.post("/api/show", json={"model": model})
            r.raise_for_status()
            return r.json()

    async def pull(self, model: str) -> AsyncIterator[dict]:
        async with self._client(None) as c:
            async with c.stream("POST", "/api/pull", json={"model": model, "stream": True}) as r:
                if r.status_code >= 400:
                    raise LLMError(f"Pull failed: HTTP {r.status_code} {(await r.aread()).decode(errors='replace')[:300]}")
                async for line in r.aiter_lines():
                    if line.strip():
                        yield json.loads(line)

    async def chat(self, model: str, messages: list[dict], *, tools: list[dict] | None = None,
                   options: dict | None = None, think: bool | None = None) -> AsyncIterator[ChatChunk]:
        body: dict[str, Any] = {
            "model": model, "messages": _ollama_messages(messages), "stream": True,
            "options": {"num_ctx": self.num_ctx, **(options or {})},
        }
        if tools:
            body["tools"] = tools
        if think is not None:
            body["think"] = think
        try:
            async with self._client(None) as c:
                async with c.stream("POST", "/api/chat", json=body) as r:
                    if r.status_code >= 400:
                        err = (await r.aread()).decode(errors="replace")
                        if r.status_code == 404 or "not found" in err.lower():
                            raise LLMError(f"Model '{model}' is not installed. Run: ollama pull {model}")
                        if "does not support thinking" in err and think is not None:
                            async for ch in self.chat(model, messages, tools=tools, options=options, think=None):
                                yield ch
                            return
                        if "does not support tools" in err and tools:
                            raise LLMError(f"Model '{model}' does not support tool calling. Pick a tool-capable model (e.g. qwen2.5-coder, qwen3, llama3.1).")
                        raise LLMError(f"Ollama error {r.status_code}: {err[:500]}")
                    async for line in r.aiter_lines():
                        if not line.strip():
                            continue
                        d = json.loads(line)
                        if d.get("error"):
                            raise LLMError(f"Ollama: {d['error']}")
                        msg = d.get("message") or {}
                        ch = ChatChunk(text=msg.get("content", "") or "", thinking=msg.get("thinking", "") or "",
                                       tool_calls=[_norm_tool_call(t) for t in msg.get("tool_calls") or []])
                        if d.get("done"):
                            ch.done = True
                            ch.stats = {k: d.get(k) for k in ("total_duration", "eval_count", "prompt_eval_count", "eval_duration")}
                        yield ch
        except httpx.ConnectError as e:
            raise LLMError(f"Cannot connect to Ollama at {self.base_url}. Is it running? Start it with 'ollama serve'.") from e

    async def embed(self, model: str, texts: list[str]) -> list[list[float]]:
        try:
            async with self._client(120) as c:
                r = await c.post("/api/embed", json={"model": model, "input": texts})
                if r.status_code == 404:
                    raise LLMError(f"Embedding model '{model}' not installed. Run: ollama pull {model}")
                r.raise_for_status()
                return r.json()["embeddings"]
        except httpx.ConnectError as e:
            raise LLMError("Cannot connect to Ollama for embeddings") from e


class OpenAICompatProvider:
    name = "cloud"

    def __init__(self, base_url: str, api_key: str = ""):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def _client(self, timeout=None) -> httpx.AsyncClient:
        h = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        return httpx.AsyncClient(base_url=self.base_url, headers=h, timeout=httpx.Timeout(timeout, connect=10.0))

    async def list_models(self) -> list[ModelInfo]:
        async with self._client(15) as c:
            r = await c.get("/models")
            r.raise_for_status()
            return [ModelInfo(name=m["id"], provider="cloud", capabilities=guess_capabilities(m["id"], {}))
                    for m in r.json().get("data", [])]

    async def chat(self, model: str, messages: list[dict], *, tools=None, options=None, think=None) -> AsyncIterator[ChatChunk]:
        oai_msgs = []
        for m in messages:
            if m.get("images"):
                parts = [{"type": "text", "text": m.get("content") or ""}] + [
                    {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b}"}} for b in m["images"]]
                oai_msgs.append({"role": m["role"], "content": parts})
            elif m["role"] == "tool":
                oai_msgs.append({"role": "tool", "content": m.get("content", ""), "tool_call_id": m.get("tool_call_id", "call_0")})
            else:
                mm = {"role": m["role"], "content": m.get("content") or ""}
                if m.get("tool_calls"):
                    mm["tool_calls"] = [{"id": t.get("id", f"call_{i}"), "type": "function",
                                         "function": {"name": t["function"]["name"], "arguments": json.dumps(t["function"]["arguments"])}}
                                        for i, t in enumerate(m["tool_calls"])]
                oai_msgs.append(mm)
        body: dict[str, Any] = {"model": model, "messages": oai_msgs, "stream": True}
        if tools:
            body["tools"] = tools
        pending: dict[int, dict] = {}
        async with self._client(None) as c:
            async with c.stream("POST", "/chat/completions", json=body) as r:
                if r.status_code >= 400:
                    raise LLMError(f"Cloud endpoint error {r.status_code}: {(await r.aread()).decode(errors='replace')[:500]}")
                async for line in r.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    d = json.loads(data)
                    for choice in d.get("choices", []):
                        delta = choice.get("delta") or {}
                        if delta.get("content"):
                            yield ChatChunk(text=delta["content"])
                        if delta.get("reasoning_content") or delta.get("reasoning"):
                            yield ChatChunk(thinking=delta.get("reasoning_content") or delta.get("reasoning"))
                        for tc in delta.get("tool_calls") or []:
                            p = pending.setdefault(tc.get("index", 0), {"id": tc.get("id", ""), "name": "", "args": ""})
                            fn = tc.get("function") or {}
                            p["name"] += fn.get("name") or ""
                            p["args"] += fn.get("arguments") or ""
        calls = []
        for p in pending.values():
            try:
                args = json.loads(p["args"] or "{}")
            except json.JSONDecodeError:
                args = {"_raw": p["args"]}
            calls.append({"id": p["id"], "function": {"name": p["name"], "arguments": args}})
        yield ChatChunk(done=True, tool_calls=calls)

    async def embed(self, model: str, texts: list[str]) -> list[list[float]]:
        async with self._client(120) as c:
            r = await c.post("/embeddings", json={"model": model, "input": texts})
            r.raise_for_status()
            return [d["embedding"] for d in r.json()["data"]]


def _norm_tool_call(t: dict) -> dict:
    fn = t.get("function") or {}
    args = fn.get("arguments")
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except json.JSONDecodeError:
            args = {"_raw": args}
    return {"id": t.get("id", ""), "function": {"name": fn.get("name", ""), "arguments": args or {}}}


VISION_HINTS = ("vision", "llava", "vl", "bakllava", "moondream", "minicpm-v", "gemma3", "llama4", "mistral-small3")
CODE_HINTS = ("coder", "code", "starcoder", "devstral", "codestral", "deepseek-coder", "codegemma")
REASON_HINTS = ("r1", "qwq", "reason", "phi4-reasoning", "magistral", "gpt-oss")
EMBED_HINTS = ("embed", "bge", "minilm", "e5", "gte", "nomic")


def guess_capabilities(name: str, details: dict) -> list[str]:
    n = name.lower()
    caps = []
    if any(h in n for h in EMBED_HINTS):
        return ["embedding"]
    caps.append("chat")
    if any(re.search(rf"(^|[-_:./]){re.escape(h)}", n) or h in n for h in VISION_HINTS):
        caps.append("vision")
    if any(h in n for h in CODE_HINTS):
        caps.append("coding")
    if any(h in n for h in REASON_HINTS) or n.startswith("qwen3"):
        caps.append("reasoning")
    if any(h in n for h in ("qwen2.5", "qwen3", "llama3.1", "llama3.2", "llama3.3", "mistral", "coder", "command-r", "gpt-oss", "granite")):
        caps.append("tools")
    return caps


class ModelRouter:
    """Chooses provider + model per request. `auto` uses the role table in settings plus heuristics."""

    def __init__(self, settings: Settings):
        self.settings = settings

    @property
    def ollama(self) -> OllamaProvider:
        return OllamaProvider(self.settings["ollama_url"], int(self.settings["num_ctx"]))

    @property
    def cloud(self) -> OpenAICompatProvider | None:
        c = self.settings["cloud"]
        if c.get("enabled") and c.get("base_url"):
            return OpenAICompatProvider(c["base_url"], c.get("api_key", ""))
        return None

    def role_for(self, mode: str, text: str = "", has_images: bool = False, has_code: bool = False) -> str:
        if has_images or mode == "vision":
            return "vision"
        if mode in ("agent", "code", "coding", "project") or has_code:
            return "coding"
        if mode == "reasoning":
            return "reasoning"
        t = text.lower()
        if re.search(r"```|\b(java|python|typescript|javascript|rust|golang|c\+\+|c#|compile|stack ?trace|exception|bug|refactor|function|class|plugin|maven|gradle|regex|sql)\b", t):
            return "coding"
        if re.search(r"\b(prove|step by step|reason|why does|derive|calculate|math|logic puzzle)\b", t):
            return "reasoning"
        return "chat"

    def resolve(self, requested: str, mode: str, text: str = "", has_images: bool = False, has_code: bool = False) -> tuple[Any, str, str]:
        """Return (provider, model_name, role)."""
        role = self.role_for(mode, text, has_images, has_code)
        if requested and requested != "auto":
            if requested.startswith("cloud:"):
                if not self.cloud:
                    raise LLMError("Cloud model selected but no cloud endpoint is configured in Settings.")
                return self.cloud, requested[6:], role
            return self.ollama, requested, role
        return self.ollama, self.settings["models"].get(role) or self.settings["models"]["chat"], role

    async def available_models(self) -> dict:
        out: dict[str, Any] = {"ollama": [], "cloud": [], "errors": []}
        try:
            out["ollama"] = [m.__dict__ for m in await self.ollama.list_models()]
        except Exception as e:  # noqa: BLE001
            out["errors"].append(f"Ollama: {e.__class__.__name__}: {e}")
        if self.cloud:
            try:
                out["cloud"] = [m.__dict__ for m in await self.cloud.list_models()]
            except Exception as e:  # noqa: BLE001
                out["errors"].append(f"Cloud: {e}")
        installed = {m["name"] for m in out["ollama"]}
        installed |= {n.split(":")[0] for n in installed if n.endswith(":latest")}
        out["roles"] = {role: {"model": name, "installed": name in installed or f"{name}:latest" in installed}
                        for role, name in self.settings["models"].items()}
        return out

    async def complete(self, messages: list[dict], *, role: str = "fast", model: str | None = None, options: dict | None = None) -> str:
        """Non-streaming helper used for titles, summaries and memory extraction."""
        prov, name, _ = (self.ollama, model or self.settings["models"].get(role) or self.settings["models"]["chat"], role)
        parts = []
        async for ch in prov.chat(name, messages, options=options or {"temperature": 0.2}, think=False):
            parts.append(ch.text)
        return strip_think("".join(parts))


def strip_think(text: str) -> str:
    return re.sub(r"<think>.*?</think>", "", text, flags=re.S).strip()
