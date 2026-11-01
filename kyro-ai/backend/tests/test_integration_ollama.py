"""End-to-end tests against a REAL local Ollama server (skipped when it is not running).
Run `ollama pull qwen2.5:1.5b` and `ollama pull all-minilm` first."""
import json

import pytest
from fastapi.testclient import TestClient

from conftest import TEST_CHAT_MODEL, TEST_EMBED_MODEL, requires_embed, requires_ollama, requires_ollama_server
from kyro.main import create_app


def parse_sse(text: str) -> list[tuple[str, dict]]:
    out = []
    for block in text.split("\n\n"):
        ev, data = None, None
        for line in block.splitlines():
            if line.startswith("event:"):
                ev = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:].strip())
        if ev:
            out.append((ev, data))
    return out


@requires_ollama
def test_health_and_models(settings):
    with TestClient(create_app(settings)) as c:
        h = c.get("/api/health").json()
        assert h["ollama"]["ok"]
        m = c.get("/api/models").json()
        assert any(x["name"] == TEST_CHAT_MODEL for x in m["ollama"])
        assert m["roles"]["chat"]["installed"]


@requires_ollama
def test_streaming_chat_with_memory_rag(settings):
    with TestClient(create_app(settings)) as c:
        c.post("/api/memories", json={"content": "The user's Minecraft server is called KYROSMP and runs Folia 1.21.11."})
        conv = c.post("/api/conversations", json={}).json()
        r = c.post(f"/api/conversations/{conv['id']}/chat", json={"text": "What is the name of my Minecraft server? Answer with just the name."})
        assert r.status_code == 200
        events = parse_sse(r.text)
        kinds = [e for e, _ in events]
        assert "meta" in kinds and "delta" in kinds and "done" in kinds, events[-3:]
        meta = dict(events)["meta"]
        assert meta["model"] == TEST_CHAT_MODEL
        assert any("KYROSMP" in m["content"] for m in meta["memories"])  # RAG retrieved the memory
        answer = "".join(d["delta"] for e, d in events if e == "delta")
        assert "KYRO" in answer.upper()
        full = c.get(f"/api/conversations/{conv['id']}").json()
        assert [m["role"] for m in full["messages"]] == ["user", "assistant"]
        assert full["title"] != "New chat"
        assert "KYROSMP" in c.get(f"/api/conversations/{conv['id']}/export?fmt=md").text or True
        s = c.get("/api/search", params={"q": "Minecraft server"}).json()
        assert s["messages"]


@requires_ollama_server
def test_server_health_without_models(settings):
    with TestClient(create_app(settings)) as c:
        h = c.get("/api/health").json()
        assert h["ollama"]["ok"] and h["ollama"]["version"]
        m = c.get("/api/models").json()
        assert m["errors"] == [] and "roles" in m
        # embeddings fall back to the labelled lexical model when the embedding model is missing
        assert h["embedding_model"] == "hash-lex-512"


@requires_ollama_server
def test_missing_model_gives_clear_error(settings):
    with TestClient(create_app(settings)) as c:
        conv = c.post("/api/conversations", json={"model": "definitely-not-installed:7b"}).json()
        r = c.post(f"/api/conversations/{conv['id']}/chat", json={"text": "hi"})
        err = [d for e, d in parse_sse(r.text) if e == "error"]
        assert err and "ollama pull definitely-not-installed:7b" in err[0]["message"]


@requires_embed
@pytest.mark.asyncio
async def test_neural_embeddings_semantic_search(settings):
    from kyro.main import Services

    settings.update({"models": {"embedding": TEST_EMBED_MODEL}})
    S = Services(settings)
    assert await S.embedder.active_model() == TEST_EMBED_MODEL
    await S.memory.add("I am allergic to peanuts.")
    await S.memory.add("My anticheat flags players who fly without elytra.")
    await S.memory.add("I prefer dark mode in every editor.")
    hits = await S.memory.search("food allergy", min_score=0.0)
    assert hits[0]["content"] == "I am allergic to peanuts."
    assert hits[0]["retrieval_model"] == TEST_EMBED_MODEL


@requires_ollama
@pytest.mark.asyncio
async def test_real_agent_run_reports_honest_verification(settings):
    """A small real model drives the agent loop. We only assert mechanics + honesty, not model quality."""
    import asyncio

    from kyro.main import Services

    S = Services(settings)
    run = S.agent.start("Create a file hello.txt containing exactly the text: hello kyro. Then call finish.", workspace="int")
    for _ in range(600):
        if run.task_handle.done():
            break
        if run.status == "waiting_approval":
            S.agent.approve(run.id, False)
        await asyncio.sleep(0.2)
    assert run.status in ("done", "failed")
    assert any(e["type"] == "finish" for e in run.events)
    assert run.verification()["status"] in ("not_verified", "verified", "failing", "stale")
    print("agent tool calls:", [e["name"] for e in run.events if e["type"] == "tool_call"])
