"""Chat orchestration: context assembly (RAG), long-context management and streaming."""
from __future__ import annotations

import json
import re
import time
from typing import AsyncIterator

from .db import Database, fts_query, new_id, now
from .embeddings import Embedder, approx_tokens, chunk_text, cosine
from .files import FileService, image_b64
from .llm import LLMError, ModelRouter, strip_think
from .memory import MemoryStore, extract_candidates

MODE_PROMPTS = {
    "chat": "",
    "reasoning": "Think carefully and systematically before answering. Check your work. State assumptions explicitly.",
    "coding": "You are an expert software engineer. Produce complete, compilable code. Use fenced code blocks with a "
              "language tag and a filename comment on the first line when creating files. Explain changes briefly. "
              "Never claim code was compiled or tested unless a tool result shows it.",
    "project": "You are working inside the user's project. Use the project instructions and project memories. "
               "Ask for missing files instead of guessing their contents.",
    "research": "Answer using the numbered web sources provided. Cite them inline like [1], [2]. If sources do not "
                "cover something, say so. Do not invent sources or URLs.",
    "vision": "Describe and analyse the provided images precisely. Transcribe visible text exactly. Say when something is unreadable.",
    "document": "Answer from the provided documents. Quote relevant passages and name the document. Say when the answer is not in them.",
    "agent": "",
}

ARTIFACT_HINT = ("When you produce a self-contained HTML/CSS/JS page, SVG or React-free demo, put it in a single ```html "
                 "code block so KYRO can render a live preview.")


def sse(event: str, data) -> str:
    return f"event: {event}\ndata: {json.dumps(data, default=str)}\n\n"


class ChatService:
    def __init__(self, db: Database, router: ModelRouter, memory: MemoryStore, files: FileService, embedder: Embedder, settings):
        self.db, self.router, self.memory, self.files, self.embedder, self.settings = db, router, memory, files, embedder, settings

    # ---------------------------------------------------------- conversations
    def create_conversation(self, title: str = "New chat", project_id: str | None = None, mode: str = "chat", model: str = "auto") -> dict:
        cid, t = new_id(), now()
        self.db.execute("INSERT INTO conversations(id,title,project_id,mode,model,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                        (cid, title, project_id, mode, model, t, t))
        return self.db.one("SELECT * FROM conversations WHERE id=?", (cid,))

    def messages(self, cid: str) -> list[dict]:
        rows = self.db.all("SELECT * FROM messages WHERE conversation_id=? ORDER BY seq", (cid,))
        for r in rows:
            r["attachments"] = json.loads(r["attachments"])
            r["meta"] = json.loads(r["meta"])
        return rows

    def add_message(self, cid: str, role: str, content: str, *, model: str = "", attachments=None, meta=None) -> dict:
        mid, t = new_id(), now()
        with self.db.transaction() as c:
            seq = c.execute("SELECT COALESCE(MAX(seq),-1)+1 FROM messages WHERE conversation_id=?", (cid,)).fetchone()[0]
            c.execute("INSERT INTO messages(id,conversation_id,seq,role,content,model,attachments,meta,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                      (mid, cid, seq, role, content, model, json.dumps(attachments or []), json.dumps(meta or {}, default=str), t))
            c.execute("INSERT INTO messages_fts(content, conversation_id, message_id) VALUES (?,?,?)", (content, cid, mid))
            c.execute("UPDATE conversations SET updated_at=? WHERE id=?", (t, cid))
        return {"id": mid, "seq": seq, "role": role, "content": content, "created_at": t}

    def search_history(self, q: str, limit: int = 50) -> list[dict]:
        fq = fts_query(q)
        if not fq:
            return []
        return self.db.all(
            "SELECT f.conversation_id, f.message_id, snippet(messages_fts, 0, '[', ']', '…', 18) AS snippet, c.title, c.source, c.updated_at "
            "FROM messages_fts f JOIN conversations c ON c.id=f.conversation_id WHERE messages_fts MATCH ? ORDER BY bm25(messages_fts) LIMIT ?",
            (fq, limit))

    def export(self, cid: str, fmt: str = "md") -> str:
        conv = self.db.one("SELECT * FROM conversations WHERE id=?", (cid,))
        msgs = self.messages(cid)
        if fmt == "json":
            return json.dumps({"conversation": conv, "messages": msgs}, indent=2, default=str)
        out = [f"# {conv['title']}", f"_source: {conv['source']} · {time.strftime('%Y-%m-%d %H:%M', time.localtime(conv['created_at']))}_", ""]
        for m in msgs:
            out.append(f"## {m['role'].capitalize()}" + (f" ({m['model']})" if m["model"] else ""))
            out.append(m["content"])
            if m["meta"].get("citations"):
                out.append("\n**Sources:** " + " ".join(f"[{c['n']}] {c['url']}" for c in m["meta"]["citations"]))
            out.append("")
        return "\n".join(out)

    # ---------------------------------------------------------- context
    async def build_context(self, conv: dict, user_text: str, attachment_ids: list[str], mode: str, web: list[dict] | None,
                            num_ctx: int) -> tuple[list[dict], dict]:
        info: dict = {"memories": [], "documents": [], "summarized": False}
        project = self.db.one("SELECT * FROM projects WHERE id=?", (conv["project_id"],)) if conv.get("project_id") else None
        personalities = self.settings["personalities"]
        persona = personalities.get(conv.get("personality") or self.settings["active_personality"]) or personalities["default"]
        sys_parts = [persona, f"Current date: {time.strftime('%Y-%m-%d')}.", ARTIFACT_HINT]
        if self.settings["custom_instructions"].strip():
            sys_parts.append("User's custom instructions:\n" + self.settings["custom_instructions"].strip())
        if MODE_PROMPTS.get(mode):
            sys_parts.append(MODE_PROMPTS[mode])
        if project:
            sys_parts.append(f"Active project: {project['name']}. {project['description']}".strip())
            if project["instructions"].strip():
                sys_parts.append("Project instructions:\n" + project["instructions"].strip())

        # ---- memory retrieval (RAG)
        if self.settings["memory"]["auto_retrieve"] and user_text.strip():
            try:
                mems = await self.memory.search(user_text, project_id=conv.get("project_id"))
            except Exception as e:  # noqa: BLE001 - never block chat on memory errors
                mems, info["memory_error"] = [], str(e)
            if mems:
                lines = []
                for m in mems:
                    when = time.strftime("%Y-%m-%d", time.localtime(m["source_time"])) if m.get("source_time") else "date unknown"
                    line = f"- ({m['scope']}/{m['kind']}, from {m['source']}, {when}) {m['content']}"
                    if m.get("conflict_note"):
                        line += " [CONFLICTS with older/other memory: " + "; ".join(c["content"][:160] for c in m["conflict_note"]) + " — prefer the newer one, mention the conflict if relevant]"
                    lines.append(line)
                sys_parts.append(
                    "Relevant memories about the user (from their imported ChatGPT/Claude history, notes and KYRO chats). "
                    "Use them when helpful; do not invent memories that are not listed; if a memory seems outdated, say so:\n" + "\n".join(lines))
                info["memories"] = [{"id": m["id"], "content": m["content"], "score": m["score"], "source": m["source"]} for m in mems]

        # ---- attachments
        images: list[str] = []
        doc_blocks: list[str] = []
        for aid in attachment_ids:
            a = self.files.full(aid)
            if not a:
                continue
            if a["kind"] == "image":
                images.append(image_b64(self.files.path(aid)))
                if a["extracted_text"].strip():
                    doc_blocks.append(f"[OCR text of image {a['filename']}]\n{a['extracted_text'][:4000]}")
                continue
            text = a["extracted_text"]
            if not text.strip():
                doc_blocks.append(f"[Attachment {a['filename']}: no extractable text. {json.loads(a['analysis']).get('note', '')}]")
                continue
            budget_chars = int(num_ctx * 3.6 * 0.35)
            if len(text) <= budget_chars:
                doc_blocks.append(f"[Attachment: {a['filename']}]\n{text}")
            else:
                chunks = chunk_text(text, 1500, 150)
                vecs, _ = await self.embedder.embed(chunks + [user_text or a["filename"]])
                q = vecs[-1]
                ranked = sorted(zip(chunks, vecs[:-1], range(len(chunks))), key=lambda x: -cosine(q, x[1]))
                picked, used = [], 0
                for ch, _, i in ranked:
                    if used + len(ch) > budget_chars:
                        break
                    picked.append((i, ch)); used += len(ch)
                picked.sort()
                doc_blocks.append(f"[Attachment: {a['filename']} — {len(picked)} of {len(chunks)} most relevant sections]\n" +
                                  "\n...\n".join(ch for _, ch in picked))
            info["documents"].append(a["filename"])
        if doc_blocks:
            sys_parts.append("Attached files:\n\n" + "\n\n".join(doc_blocks))
        if web:
            sys_parts.append("Web sources (cite as [n]):\n" + "\n\n".join(
                f"[{w['n']}] {w['title']} — {w['url']}\n{(w.get('content') or w.get('snippet') or '')[:3500]}" for w in web))

        system = "\n\n".join(p for p in sys_parts if p)

        # ---- history with automatic summarisation
        history = [m for m in self.messages(conv["id"]) if m["role"] in ("user", "assistant")]
        budget = int(num_ctx * 0.92) - approx_tokens(system) - approx_tokens(user_text) - 1024
        summary = conv.get("summary") or ""
        upto = conv.get("summary_upto") or 0
        recent = [m for m in history if m["seq"] >= upto]
        total = sum(approx_tokens(m["content"]) for m in recent) + approx_tokens(summary)
        if total > budget and len(recent) > 4:
            keep, used = [], approx_tokens(summary)
            for m in reversed(recent):
                t = approx_tokens(m["content"])
                if used + t > budget * 0.6 and len(keep) >= 2:
                    break
                keep.insert(0, m); used += t
            to_sum = [m for m in recent if m["seq"] < keep[0]["seq"]]
            if to_sum:
                summary = await self._summarize(summary, to_sum)
                upto = keep[0]["seq"]
                self.db.execute("UPDATE conversations SET summary=?, summary_upto=? WHERE id=?", (summary, upto, conv["id"]))
                info["summarized"] = True
            recent = keep
        # hard trim if still too large (e.g. summariser unavailable)
        while recent and sum(approx_tokens(m["content"]) for m in recent) > budget:
            recent = recent[1:]
        if summary:
            system += "\n\nSummary of the earlier part of this conversation:\n" + summary

        msgs = [{"role": "system", "content": system}]
        msgs += [{"role": m["role"], "content": m["content"]} for m in recent]
        user_msg = {"role": "user", "content": user_text}
        if images:
            user_msg["images"] = images
        msgs.append(user_msg)
        info["context_tokens"] = sum(approx_tokens(m["content"]) for m in msgs)
        return msgs, info

    async def _summarize(self, previous: str, msgs: list[dict]) -> str:
        transcript = "\n".join(f"{m['role'].upper()}: {m['content'][:4000]}" for m in msgs)
        prompt = ("Update the running summary of a conversation. Keep all decisions, facts about the user, code/file names, "
                  "open tasks and constraints. Be concise (max 400 words). Do not add anything not in the text.\n\n"
                  f"PREVIOUS SUMMARY:\n{previous or '(none)'}\n\nNEW MESSAGES:\n{transcript[:24000]}\n\nUPDATED SUMMARY:")
        try:
            return await self.router.complete([{"role": "user", "content": prompt}], role="summarize")
        except Exception:  # noqa: BLE001 - fall back to truncation only
            return previous

    # ---------------------------------------------------------- streaming turn
    async def stream_turn(self, cid: str, text: str, *, attachment_ids: list[str] | None = None, mode: str | None = None,
                          model: str | None = None, web_search: bool = False, think: bool | None = None,
                          regenerate: bool = False) -> AsyncIterator[str]:
        conv = self.db.one("SELECT * FROM conversations WHERE id=?", (cid,))
        if not conv:
            yield sse("error", {"message": "Conversation not found"}); return
        attachment_ids = attachment_ids or []
        mode = mode or conv["mode"]
        model = model or conv["model"]
        if regenerate:
            last = self.db.all("SELECT * FROM messages WHERE conversation_id=? ORDER BY seq DESC LIMIT 2", (cid,))
            if last and last[0]["role"] == "assistant":
                self.db.execute("DELETE FROM messages WHERE id=?", (last[0]["id"],))
                self.db.execute("DELETE FROM messages_fts WHERE message_id=?", (last[0]["id"],))
                last = last[1:]
            if not last or last[0]["role"] != "user":
                yield sse("error", {"message": "Nothing to regenerate"}); return
            text, attachment_ids = last[0]["content"], json.loads(last[0]["attachments"])
            self.db.execute("DELETE FROM messages WHERE id=?", (last[0]["id"],))
            self.db.execute("DELETE FROM messages_fts WHERE message_id=?", (last[0]["id"],))

        kinds = {(self.files.full(a) or {}).get("kind") for a in attachment_ids}
        try:
            provider, model_name, role = self.router.resolve(model, mode, text, "image" in kinds, bool(kinds & {"code", "jar", "zip"}))
        except LLMError as e:
            yield sse("error", {"message": str(e)}); return

        web = None
        if web_search or mode == "research":
            yield sse("status", {"message": "Searching the web…"})
            try:
                from .websearch import research
                web = await research(text, searxng_url=self.settings["searxng_url"])
                yield sse("citations", [{"n": w["n"], "title": w["title"], "url": w["url"]} for w in web])
            except Exception as e:  # noqa: BLE001
                yield sse("status", {"message": f"Web search failed: {e}. Answering without web sources."})

        user_row = self.add_message(cid, "user", text, attachments=attachment_ids)
        self._explicit_memory(text, conv, user_row["id"])
        msgs, info = await self.build_context(conv, text, attachment_ids, mode, web, int(self.settings["num_ctx"]))
        yield sse("meta", {"model": model_name, "provider": provider.name, "role": role, "user_message_id": user_row["id"],
                           "memories": info["memories"], "documents": info["documents"], "summarized": info["summarized"],
                           "context_tokens": info["context_tokens"]})
        parts, thinking, stats, err = [], [], {}, None
        t0 = time.time()
        try:
            want_think = think if think is not None else (True if mode == "reasoning" else None)
            async for ch in provider.chat(model_name, msgs, think=want_think):
                if ch.thinking:
                    thinking.append(ch.thinking)
                    yield sse("thinking", {"delta": ch.thinking})
                if ch.text:
                    parts.append(ch.text)
                    yield sse("delta", {"delta": ch.text})
                if ch.done:
                    stats = ch.stats
        except LLMError as e:
            err = str(e)
        except Exception as e:  # noqa: BLE001
            err = f"{e.__class__.__name__}: {e}"
        content = "".join(parts)
        if err and not content:
            yield sse("error", {"message": err, "model": model_name})
            return
        meta = {"role": role, "provider": provider.name, "memories": info["memories"], "documents": info["documents"],
                "thinking": "".join(thinking)[:20000], "stats": stats, "elapsed": round(time.time() - t0, 2)}
        if web:
            meta["citations"] = [{"n": w["n"], "title": w["title"], "url": w["url"]} for w in web]
        if err:
            meta["error"] = err
        row = self.add_message(cid, "assistant", content, model=model_name, meta=meta)
        if conv["title"] in ("New chat", "") and not regenerate:
            title = await self._title(text, content)
            self.db.execute("UPDATE conversations SET title=? WHERE id=?", (title, cid))
            yield sse("title", {"title": title})
        if self.settings["memory"]["auto_extract_from_chats"]:
            for sent, kind in extract_candidates(text):
                await self.memory.add(sent, kind=kind, scope="project" if conv["project_id"] else "general",
                                      project_id=conv["project_id"], source="kyro-chat", source_ref=cid, source_time=now())
        yield sse("done", {"message_id": row["id"], "model": model_name, "stats": stats, "error": err})

    def _explicit_memory(self, text: str, conv: dict, msg_id: str) -> None:
        """'Remember that …' is an explicit instruction from the user; store it (async-safe fire later)."""
        m = re.match(r"^\s*(?:please\s+)?(?:remember(?: that)?|merke? dir(?:,)?(?: dass)?)\s*[:,]?\s*(.+)$", text, re.I | re.S)
        if m and len(m.group(1).strip()) > 3:
            import asyncio

            asyncio.ensure_future(self.memory.add(m.group(1).strip(), kind="instruction",
                                                  scope="project" if conv["project_id"] else "general", project_id=conv["project_id"],
                                                  source="kyro-chat", source_ref=f"{conv['id']}:{msg_id}", source_time=now(), verified=True))

    async def _title(self, user: str, answer: str) -> str:
        try:
            t = await self.router.complete([{"role": "user", "content":
                "Write a short title (max 6 words, no quotes, no punctuation at the end) for this conversation.\n\n"
                f"User: {user[:1500]}\nAssistant: {answer[:800]}\n\nTitle:"}], role="fast", options={"temperature": 0.2, "num_predict": 24})
            t = strip_think(t).strip().strip('"').splitlines()[0][:80] if t.strip() else ""
        except Exception:  # noqa: BLE001
            t = ""
        return t or (user.strip().splitlines()[0][:60] if user.strip() else "New chat")
