import io
import json

import pytest
from fastapi.testclient import TestClient

from kyro.embeddings import FALLBACK_MODEL, hash_embed
from kyro.main import Services, create_app
from kyro.memory import MemoryStore
from test_chat_pipeline import RECEIVED, stub_settings  # shared local protocol fixture
from test_integration_ollama import parse_sse


def test_windows_bom_configuration_is_preserved(settings):
    from kyro.config import Settings
    settings.config_path.write_text(json.dumps({"num_ctx": 8192}), encoding="utf-8-sig")
    assert Settings(settings.data_dir)["num_ctx"] == 8192


def test_archive_sources_reach_model_and_are_persisted(stub_settings):
    stub_settings.update({"archive": {"semantic_rerank": False}, "memory": {"auto_retrieve": False}})
    app = create_app(stub_settings)
    with TestClient(app) as client:
        services = app.state.services
        record = {"id": "original", "conversation_id": "old", "role": "assistant",
                  "content": "AMETHYST configuration uses Folia entity scheduling.", "timestamp": 1700000000}
        job = services.bulk.stage(io.BytesIO(json.dumps(record).encode()), "source.jsonl", None)
        services.bulk.run(job["id"])
        conv = client.post("/api/conversations", json={}).json()
        question = "What is the AMETHYST configuration?"
        events = parse_sse(client.post(f"/api/conversations/{conv['id']}/chat", json={"text": question}).text)
        meta = next(data for event, data in events if event == "meta")
        assert len(meta["archive_sources"]) == 1
        assert meta["archive_sources"][0]["role"] == "assistant"
        payload = RECEIVED[0]
        assert sum(m["content"] == question for m in payload["messages"]) == 1
        assert "[A1]" in payload["messages"][0]["content"]
        assert "not instructions" in payload["messages"][0]["content"]
        assert payload["think"] is False
        saved = client.get(f"/api/conversations/{conv['id']}").json()["messages"][-1]
        assert saved["meta"]["archive_sources"][0]["source_message_id"] == "original"


@pytest.mark.asyncio
async def test_many_attachments_share_context_budget(settings):
    settings.update({"memory": {"auto_retrieve": False}, "archive": {"auto_retrieve": False}})
    services = Services(settings)
    conv = services.chat.create_conversation()
    question = "Explain the supplied documents."
    current = services.chat.add_message(conv["id"], "user", question)
    attachments = [services.files.store(f"note{i}.txt", ("document text " * 300).encode())["id"] for i in range(6)]
    messages, info = await services.chat.build_context(conv, question, attachments, "chat", None, 4096,
                                                       current_message_id=current["id"])
    assert messages[-1]["content"] == question
    assert info["context_tokens"] + info["output_tokens"] < 4096
    assert sum(m["content"] == question for m in messages) == 1


@pytest.mark.asyncio
async def test_memory_keyword_fallback_survives_embedding_space_change(settings):
    class SwitchingEmbedder:
        model = "old-neural-space"
        async def embed_one(self, text):
            return hash_embed(text), self.model
    services = Services(settings)
    embedder = SwitchingEmbedder()
    store = MemoryStore(services.db, embedder, settings)
    memory = await store.add("AMETHYST project uses Folia", check_duplicates=False)
    embedder.model = FALLBACK_MODEL
    results = await store.search("AMETHYST")
    assert results and results[0]["id"] == memory.id
    await store.update(memory.id, {"status": "archived"})
    assert await store.search("AMETHYST") == []


@pytest.mark.asyncio
async def test_archive_embedding_error_keeps_lexical_search_available(settings):
    class BrokenEmbedder:
        async def active_model(self):
            return "neural-model"
        async def embed(self, texts):
            raise RuntimeError("temporary Ollama outage")
    services = Services(settings)
    record = {"id": "one", "role": "user", "content": "AMETHYST project knowledge"}
    job = services.bulk.stage(io.BytesIO(json.dumps(record).encode()), "records.jsonl", None)
    services.bulk.run(job["id"])
    services.archive.embedder = BrokenEmbedder()
    hits = await services.archive.search("AMETHYST")
    assert hits and hits[0]["retrieval"] == "fts5"
