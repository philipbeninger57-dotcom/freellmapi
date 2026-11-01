import io
import json
import threading
import time
import zipfile

import pytest
from fastapi.testclient import TestClient

from kyro.archive import ArchiveStore, index_message
from kyro.bulk_import import BulkImports, json_objects
from kyro.db import Database
from kyro.embeddings import hash_embed
from kyro.importers import ensure_project
from kyro.main import Services, create_app
from test_importers import CHATGPT, CLAUDE


def flat_records(count=7, **overrides):
    return [{"id": f"m{i}", "conversation_id": "thread", "source": "dataset", "role": "user",
             "content": f"Folia scheduler record {i}", "timestamp": 1700000000 + i, **overrides} for i in range(count)]


def stage(service, records, project_id=None, filename="messages.jsonl"):
    body = b"\n".join(json.dumps(r).encode() for r in records)
    return service.stage(io.BytesIO(body), filename, project_id)


def run_job(service, job):
    service.run(job["id"])
    result = service.get(job["id"])
    assert result["status"] == "done", result
    return result


def test_json_readers_stream_without_unbounded_reads():
    class Guarded(io.BytesIO):
        def read(self, size=-1):
            assert size >= 0, "unbounded read"
            return super().read(size)
    for wrapped in (False, True):
        records = flat_records(2000)
        body = json.dumps({"version": 1, "conversations": records} if wrapped else records).encode()
        stream = Guarded(body)
        iterator = json_objects(stream)
        assert next(iterator)["id"] == "m0"
        assert stream.tell() < len(body)
        assert len(list(iterator)) == 1999
    assert list(json_objects(Guarded(b'{"id":"one","messages":[]}')))[0]["id"] == "one"
    with pytest.raises(ValueError, match="line 2"):
        list(json_objects(io.BytesIO(b'{"id":1}\nnot json\n'), jsonl=True))


def test_bulk_idempotent_updates_and_no_personal_facts(settings):
    services = Services(settings)
    bulk = services.bulk
    records = flat_records()
    result = run_job(bulk, stage(bulk, records))
    assert result["inserted"] == 7
    assert services.db.one("SELECT COUNT(*) n FROM archive_chunks")["n"] == 7
    assert services.db.one("SELECT COUNT(*) n FROM memories")["n"] == 0
    assert list(bulk.directory.glob("*.upload")) == []  # completed upload removed
    again = run_job(bulk, stage(bulk, records))
    assert again["duplicates"] == 7 and again["inserted"] == 0
    records[0]["content"] = "NewUniqueValue teleportAsync"
    changed = run_job(bulk, stage(bulk, records))
    assert changed["updated"] == 1 and changed["duplicates"] == 6
    assert services.db.one("SELECT COUNT(*) n FROM messages")["n"] == 7
    assert services.db.one("SELECT COUNT(*) n FROM archive_fts WHERE archive_fts MATCH 'NewUniqueValue'")["n"] == 1
    assert services.db.one("SELECT COUNT(*) n FROM messages_fts")["n"] == 7
    assert services.db.one("SELECT COUNT(*) n FROM message_fts_rows")["n"] == 7


def test_incremental_updates_keep_original_times_when_new_time_is_missing(settings):
    services = Services(settings)
    record = flat_records(1)[0]
    run_job(services.bulk, stage(services.bulk, [record]))
    original = record.pop("timestamp")
    record["content"] = "Updated content"
    assert run_job(services.bulk, stage(services.bulk, [record]))["updated"] == 1
    row = services.db.one("SELECT created_at,meta FROM messages")
    assert row["created_at"] == original
    assert json.loads(row["meta"])["source_time"] == original
    record["timestamp"] = 1700000999
    assert run_job(services.bulk, stage(services.bulk, [record]))["updated"] == 1
    assert services.db.one("SELECT created_at FROM messages")["created_at"] == 1700000999


def test_pause_resume_and_transactional_checkpoint(settings, monkeypatch):
    settings.update({"archive": {"batch_size": 2}})
    bulk = BulkImports(Database(settings.db_path), settings)
    job = stage(bulk, flat_records())
    flag = threading.Event()
    write = bulk._write_batch
    def pause_after_batch(*args):
        write(*args)
        flag.set()
    monkeypatch.setattr(bulk, "_write_batch", pause_after_batch)
    bulk.run(job["id"], flag)
    paused = bulk.get(job["id"])
    assert paused["status"] == "paused" and paused["checkpoint"] == 2
    assert len(list(bulk.directory.glob("*.upload"))) == 1
    # Simulate a new process with only persisted state.
    restarted = BulkImports(Database(settings.db_path), settings)
    result = run_job(restarted, paused)
    assert result["inserted"] == result["processed"] == 7
    assert result["duplicates"] == 0


def test_failure_rolls_back_batch_and_resume_replays_it(settings, monkeypatch):
    import kyro.bulk_import as module
    settings.update({"archive": {"batch_size": 2}})
    bulk = BulkImports(Database(settings.db_path), settings)
    job = stage(bulk, flat_records())
    original = module.index_message
    def fail(conn, mid, text):
        if text.endswith("3"):
            raise RuntimeError("simulated disk error")
        return original(conn, mid, text)
    monkeypatch.setattr(module, "index_message", fail)
    bulk.run(job["id"])
    failed = bulk.get(job["id"])
    assert failed["status"] == "failed" and failed["checkpoint"] == 2
    assert bulk.db.one("SELECT COUNT(*) n FROM messages")["n"] == 2
    monkeypatch.setattr(module, "index_message", original)
    result = run_job(bulk, failed)
    assert result["processed"] == result["inserted"] == 7


@pytest.mark.parametrize("shape", ["array", "wrapped", "single", "zip", "jsonl"])
def test_export_formats_and_source_ids(settings, shape):
    bulk = BulkImports(Database(settings.db_path), settings)
    records = CHATGPT + CLAUDE
    filename = "export.json"
    if shape == "array":
        body = json.dumps(records).encode()
    elif shape == "wrapped":
        body = json.dumps({"conversations": records}).encode()
    elif shape == "single":
        body = json.dumps(CHATGPT[0]).encode()
    elif shape == "jsonl":
        body = b"\n".join(json.dumps(r).encode() for r in records)
        filename = "export.jsonl"
    else:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("shard-a.json", json.dumps(CHATGPT))
            archive.writestr("shard-b.json", json.dumps(CLAUDE))
            archive.writestr("projects.json", json.dumps([{"name": "not a conversation"}]))
        body, filename = buffer.getvalue(), "export.zip"
    result = run_job(bulk, bulk.stage(io.BytesIO(body), filename, None))
    assert result["inserted"] == (3 if shape == "single" else 5)
    rows = bulk.db.all("SELECT role,content,meta,created_at FROM messages")
    assert not any("ABANDONED BRANCH" in r["content"] for r in rows)
    assert any(json.loads(r["meta"])["source_message_id"] == "n1" and r["created_at"] == 1700000001 for r in rows)


@pytest.mark.asyncio
async def test_standard_import_then_bulk_does_not_duplicate(settings, tmp_path):
    services = Services(settings)
    path = tmp_path / "conversations.json"
    path.write_text(json.dumps(CHATGPT))
    await services.importer.import_file(path, path.name)
    result = run_job(services.bulk, services.bulk.stage(io.BytesIO(path.read_bytes()), path.name, None))
    assert result["duplicates"] == 3
    assert services.db.one("SELECT COUNT(*) n FROM messages")["n"] == 3


def test_legacy_message_ids_are_adopted(settings):
    services = Services(settings)
    conv = services.chat.create_conversation()
    services.db.execute("UPDATE conversations SET source='chatgpt',source_id='c-1' WHERE id=?", (conv["id"],))
    from kyro.importers import parse_chatgpt_conversations
    original = []
    for message in parse_chatgpt_conversations(CHATGPT)[0]["messages"]:
        original.append(services.chat.add_message(conv["id"], message["role"], message["content"])["id"])
    job = services.bulk.stage(io.BytesIO(json.dumps(CHATGPT).encode()), "export.json", None)
    assert run_job(services.bulk, job)["duplicates"] == 3
    assert {r["id"] for r in services.db.all("SELECT id FROM messages")} == set(original)


def test_limits_and_interruption_recovery(settings):
    bulk = BulkImports(Database(settings.db_path), settings)
    with pytest.raises(ValueError, match="Project"):
        stage(bulk, flat_records(), project_id="missing")
    with pytest.raises(ValueError, match="accepts"):
        stage(bulk, flat_records(), filename="file.exe")
    settings.update({"archive": {"max_import_gb": 0.0000001}})
    with pytest.raises(ValueError, match="max_import"):
        stage(bulk, flat_records())
    assert list(bulk.directory.iterdir()) == []
    settings.update({"archive": {"max_import_gb": 20}})
    job = stage(bulk, flat_records())
    bulk.db.execute("UPDATE archive_jobs SET status='running' WHERE id=?", (job["id"],))
    bulk.recover()
    assert bulk.get(job["id"])["status"] == "interrupted"


def test_job_lock_prevents_false_recovery_and_duplicate_workers(settings):
    bulk = BulkImports(Database(settings.db_path), settings)
    other = BulkImports(Database(settings.db_path), settings)
    job = stage(bulk, flat_records(2))
    with bulk._job_lock(job["id"]) as acquired:
        assert acquired
        bulk.db.execute("UPDATE archive_jobs SET status='running' WHERE id=?", (job["id"],))
        other.recover()
        assert other.get(job["id"])["status"] == "running"
        other.run(job["id"])
        assert other.db.one("SELECT COUNT(*) n FROM messages")["n"] == 0
    other.recover()
    assert other.get(job["id"])["status"] == "interrupted"
    assert run_job(other, job)["inserted"] == 2


def test_pause_from_another_manager_is_durable(settings):
    settings.update({"archive": {"batch_size": 2}})
    other = BulkImports(Database(settings.db_path), settings)
    def progress(job):
        other.pause(job["id"])
        with pytest.raises(ValueError, match="Pause"):
            other.delete(job["id"])
    bulk = BulkImports(Database(settings.db_path), settings, progress)
    job = stage(bulk, flat_records(7))
    bulk.run(job["id"])
    assert other.get(job["id"])["status"] == "paused"
    assert other.get(job["id"])["checkpoint"] == 2
    assert run_job(other, job)["inserted"] == 7


@pytest.mark.asyncio
async def test_project_scoping_and_delete_cascade(settings):
    settings.update({"archive": {"semantic_rerank": False, "candidate_limit": 2}})
    services = Services(settings)
    a, b = ensure_project(services.db, "A"), ensure_project(services.db, "B")
    run_job(services.bulk, stage(services.bulk, flat_records(1, conversation_id="general", content="Needle general")))
    run_job(services.bulk, stage(services.bulk, flat_records(1, conversation_id="a", content="Needle projectA"), a))
    run_job(services.bulk, stage(services.bulk, flat_records(300, conversation_id="b", content="Needle Needle Needle projectB"), b))
    assert all(r["project_id"] is None for r in await services.archive.search("Needle"))
    scoped = await services.archive.search("Needle", project_id=a)
    assert {r["project_id"] for r in scoped} == {None, a}
    for row in scoped:
        services.db.execute("DELETE FROM conversations WHERE id=?", (row["conversation_id"],))
    assert await services.archive.search("Needle", project_id=a) == []


@pytest.mark.asyncio
async def test_vectors_persist_and_changed_chunks_invalidate_cache(settings):
    class Embedder:
        calls = []
        async def active_model(self):
            return "nomic-embed-text"
        async def embed(self, texts):
            self.calls.append(texts)
            return [hash_embed(text) for text in texts], "nomic-embed-text"
    services = Services(settings)
    run_job(services.bulk, stage(services.bulk, flat_records(2)))
    embedder = Embedder()
    store = ArchiveStore(services.db, embedder, settings)
    first = await store.search("Folia scheduler")
    assert first and first[0]["retrieval"] == "fts5+semantic_rerank"
    assert len(embedder.calls[0]) == 3
    assert embedder.calls[0][0].startswith("search_query: ")
    assert embedder.calls[0][1].startswith("search_document: ")
    second = ArchiveStore(services.db, embedder, settings)
    await second.search("Folia scheduler")
    assert len(embedder.calls[-1]) == 1  # only the new query is embedded
    mid = first[0]["message_id"]
    with services.db.transaction() as conn:
        index_message(conn, mid, "Changed source")
    assert services.db.one("SELECT COUNT(*) n FROM archive_embeddings")["n"] == 1


def test_reindex_existing_messages_is_idempotent(settings):
    services = Services(settings)
    conv = services.chat.create_conversation()
    services.chat.add_message(conv["id"], "user", "Existing historical source")
    services.db.execute("DELETE FROM archive_chunks")  # simulate an installation before the archive index
    run_job(services.bulk, services.bulk.reindex())
    run_job(services.bulk, services.bulk.reindex())
    assert services.db.one("SELECT COUNT(*) n FROM archive_chunks")["n"] == 1


def test_background_api_and_source_pagination(settings):
    settings.update({"archive": {"semantic_rerank": False}})
    with TestClient(create_app(settings)) as client:
        body = b"\n".join(json.dumps(r).encode() for r in flat_records(350))
        response = client.post("/api/archive/jobs", files={"file": ("messages.jsonl", body)})
        assert response.status_code == 202
        jid = response.json()["id"]
        for _ in range(200):
            job = client.get(f"/api/archive/jobs/{jid}").json()
            if job["status"] in ("done", "failed"):
                break
            time.sleep(0.01)
        assert job["status"] == "done" and job["inserted"] == 350, job
        assert "file_path" not in job
        hits = client.get("/api/archive/search", params={"q": "scheduler"}).json()
        source = hits[0]
        cid = source["conversation_id"]
        tail = client.get(f"/api/conversations/{cid}").json()
        assert len(tail["messages"]) == 200 and tail["has_older"]
        focus = client.get(f"/api/conversations/{cid}", params={"message": source["message_id"], "limit": 20}).json()
        assert len(focus["messages"]) <= 20
        assert source["message_id"] in {m["id"] for m in focus["messages"]}
        assert client.delete(f"/api/archive/jobs/{jid}").status_code == 200
        assert client.get(f"/api/conversations/{cid}").status_code == 200
