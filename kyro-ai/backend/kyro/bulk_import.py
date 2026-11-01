"""Restartable, disk-backed archive imports. No LLM or per-memory duplicate scan.

JSON arrays are parsed one conversation/object at a time. JSONL accepts one
conversation or one message per line. Transactions also commit the checkpoint,
so retrying an interrupted job cannot skip an uncommitted batch.
"""
from __future__ import annotations

import asyncio
import hashlib
import io
import json
import threading
import zipfile
from collections import OrderedDict
from contextlib import contextmanager
from pathlib import Path
from typing import BinaryIO, Callable, Iterator

import ijson
from ijson.common import ObjectBuilder

from .archive import index_message
from .db import Database, index_history_message, new_id, now, stable_id
from .importers import parse_chatgpt_conversations, parse_claude_conversations, parse_time

MAX_RECORD_BYTES = 32 * 1024 * 1024
JSON_SUFFIXES = (".json", ".jsonl", ".ndjson")


def _timestamp(*values):
    return next((value for value in values if value is not None), None)


def _array_items(events, item_prefix: str) -> Iterator[dict]:
    builder, depth, size = None, 0, 0
    for prefix, event, value in events:
        if builder is None:
            if prefix == item_prefix and event == "start_map":
                builder, depth, size = ObjectBuilder(), 0, 0
            elif prefix == item_prefix and event not in ("end_array", "end_map"):
                # Mixed provider ZIPs may also contain scalar memory lists.
                # Count those as unsupported records, never as personal facts.
                yield {}
                continue
            else:
                continue
        size += len(value.encode()) if isinstance(value, str) else 8
        if size > MAX_RECORD_BYTES:
            raise ValueError("One conversation exceeds 32 MiB; split it into message JSONL records")
        builder.event(event, value)
        if event in ("start_map", "start_array"):
            depth += 1
        elif event in ("end_map", "end_array"):
            depth -= 1
        if depth == 0:
            yield builder.value
            builder = None


def json_objects(stream: BinaryIO, jsonl: bool = False) -> Iterator[dict]:
    if jsonl:
        line_no = 0
        while True:
            line = stream.readline(MAX_RECORD_BYTES + 1)
            if not line:
                return
            line_no += 1
            if len(line) > MAX_RECORD_BYTES:
                raise ValueError(f"JSONL line {line_no} exceeds 32 MiB")
            if not line.strip():
                continue
            try:
                obj = json.loads(line.decode("utf-8-sig"))
            except (ValueError, UnicodeError) as exc:
                raise ValueError(f"Invalid JSONL at line {line_no}: {exc}") from exc
            if not isinstance(obj, dict):
                raise ValueError(f"JSONL line {line_no} must contain an object")
            yield obj
        return
    stream = stream if hasattr(stream, "peek") else io.BufferedReader(stream)
    if stream.peek(3).startswith(b"\xef\xbb\xbf"):
        stream.read(3)
    events = iter(ijson.parse(stream, use_float=True))
    first = next(events, None)
    if first is None:
        return
    if first == ("", "start_array", None):
        yield from _array_items(events, "item")
        return
    if first != ("", "start_map", None):
        raise ValueError("Expected a JSON array, conversation object, or JSONL file")
    builder, size = ObjectBuilder(), 0
    builder.event(first[1], first[2])
    for prefix, event, value in events:
        if event == "start_array" and prefix in ("conversations", "items", "data"):
            yield from _array_items(events, prefix + ".item")
            return
        size += len(value.encode()) if isinstance(value, str) else 8
        if size > MAX_RECORD_BYTES:
            raise ValueError("One conversation exceeds 32 MiB; split it into message JSONL records")
        builder.event(event, value)
    yield builder.value


def _normalise(obj: dict, namespace: str, ordinal: int) -> Iterator[dict | None]:
    """Flatten one record while preserving source IDs, roles and original times."""
    if "mapping" in obj:
        raw = {**obj, "id": obj.get("id") or obj.get("conversation_id") or stable_id(namespace, ordinal)}
        source, convs = "chatgpt", parse_chatgpt_conversations([raw])
    elif "chat_messages" in obj or isinstance(obj.get("messages"), list):
        raw = {**obj, "id": obj.get("id") or obj.get("conversation_id") or stable_id(namespace, ordinal)}
        source = str(obj.get("source") or ("claude" if "chat_messages" in obj else "dataset"))[:80]
        convs = parse_claude_conversations([raw])
    elif obj.get("role") or obj.get("sender"):
        role = {"human": "user", "bot": "assistant"}.get(obj.get("role") or obj.get("sender"), obj.get("role") or obj.get("sender"))
        content = obj.get("content") or obj.get("text")
        if role not in ("user", "assistant", "system", "tool") or not isinstance(content, str) or not content.strip():
            yield None
            return
        yield {
            "source": str(obj.get("source") or "dataset")[:80],
            "conversation": str(obj.get("conversation_id") or obj.get("thread_id") or namespace),
            "title": str(obj.get("title") or "Imported messages")[:300],
            "created": parse_time(_timestamp(obj.get("timestamp"), obj.get("created_at"))),
            "id": str(obj.get("id") or obj.get("message_id") or stable_id(namespace, ordinal)),
            "role": role, "content": content,
            "time": parse_time(_timestamp(obj.get("timestamp"), obj.get("created_at"))),
        }
        return
    else:
        yield None
        return
    if not convs:
        yield None
    for conv in convs:
        for seq, message in enumerate(conv["messages"]):
            yield {"source": source, "conversation": conv["id"], "title": conv["title"],
                   "created": conv["created"], "updated": conv["updated"],
                   "source_seq": seq,
                   "id": str(message.get("id") or stable_id(conv["id"], seq)), **message}


def archive_records(path: Path, filename: str, file_hash: str, max_bytes: int) -> Iterator[dict | None]:
    def read(stream, name):
        namespace = stable_id(file_hash, name)
        for ordinal, obj in enumerate(json_objects(stream, name.lower().endswith((".jsonl", ".ndjson")))):
            yield from _normalise(obj, namespace, ordinal)
    if filename.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) > 100_000 or sum(e.file_size for e in entries) > max_bytes:
                raise ValueError("Archive exceeds the configured expanded-size/file-count limit")
            for entry in sorted(entries, key=lambda e: e.filename):
                if entry.is_dir() or not entry.filename.lower().endswith(JSON_SUFFIXES):
                    continue
                with archive.open(entry) as stream:
                    yield from read(stream, entry.filename)
    else:
        with path.open("rb") as stream:
            yield from read(stream, filename)


class BulkImports:
    def __init__(self, db: Database, settings, progress: Callable[[dict], None] | None = None):
        self.db, self.settings = db, settings
        self.progress = progress
        self.directory = settings.data_dir / "archive-imports"
        self.directory.mkdir(exist_ok=True)
        self._task: asyncio.Task | None = None
        self._pauses: dict[str, threading.Event] = {}

    def recover(self) -> None:
        for row in self.db.all("SELECT id FROM archive_jobs WHERE status IN ('running','pausing')"):
            with self._job_lock(row["id"]) as acquired:
                if acquired:
                    self.db.execute("UPDATE archive_jobs SET status='interrupted',error='Interrupted; resume to continue',updated_at=? WHERE id=? AND status IN ('running','pausing')",
                                    (now(), row["id"]))

    @contextmanager
    def _job_lock(self, jid: str):
        """An OS lock also coordinates the CLI and a running web application."""
        import errno
        import sys
        with (self.directory / (jid + ".lock")).open("a+b") as lock:
            if lock.tell() == 0:
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            try:
                if sys.platform == "win32":
                    import msvcrt
                    msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                if exc.errno not in (errno.EACCES, errno.EAGAIN, errno.EDEADLK):
                    raise
                yield False
                return
            try:
                yield True
            finally:
                lock.seek(0)
                if sys.platform == "win32":
                    msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def stage(self, stream: BinaryIO, filename: str, project_id: str | None, *, initial_status: str = "queued") -> dict:
        if not filename.lower().endswith((*JSON_SUFFIXES, ".zip")):
            raise ValueError("Archive import accepts .json, .jsonl, .ndjson and .zip")
        if project_id and not self.db.one("SELECT id FROM projects WHERE id=?", (project_id,)):
            raise ValueError("Project not found")
        jid = new_id()
        target = self.directory / (jid + ".upload")
        digest, total = hashlib.sha256(), 0
        maximum = int(float(self.settings["archive"]["max_import_gb"]) * 1024**3)
        try:
            with target.open("xb") as out:
                while block := stream.read(1024 * 1024):
                    total += len(block)
                    if total > maximum:
                        raise ValueError("Import exceeds archive.max_import_gb")
                    digest.update(block)
                    out.write(block)
            t = now()
            self.db.execute(
                "INSERT INTO archive_jobs(id,filename,file_path,file_hash,project_id,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                (jid, Path(filename.replace("\\", "/")).name, str(target), digest.hexdigest(), project_id, initial_status, t, t))
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        return self.get(jid)

    def get(self, jid: str) -> dict | None:
        row = self.db.one("SELECT * FROM archive_jobs WHERE id=?", (jid,))
        if row:
            row.pop("file_path", None)
            row.pop("file_hash", None)
        return row

    def start_worker(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._drain())

    async def _drain(self) -> None:
        while job := self.db.one("SELECT * FROM archive_jobs WHERE status='queued' ORDER BY created_at LIMIT 1"):
            flag = self._pauses.setdefault(job["id"], threading.Event())
            try:
                await asyncio.to_thread(self.run, job["id"], flag)
            except Exception as exc:
                self.db.execute("UPDATE archive_jobs SET status='failed',error=?,updated_at=? WHERE id=?",
                                (f"{exc.__class__.__name__}: {exc}"[:1500], now(), job["id"]))
            finally:
                self._pauses.pop(job["id"], None)

    async def shutdown(self) -> None:
        for event in self._pauses.values():
            event.set()
        if self._task and not self._task.done():
            # Queued jobs stay queued. An active job commits its batch then pauses.
            self.db.execute("UPDATE archive_jobs SET status='paused',updated_at=? WHERE status='queued'", (now(),))
            await self._task

    def pause(self, jid: str) -> None:
        job = self.get(jid)
        if not job:
            raise KeyError(jid)
        if job["status"] == "queued":
            self.db.execute("UPDATE archive_jobs SET status='paused',updated_at=? WHERE id=?", (now(), jid))
        elif job["status"] == "running":
            self._pauses.setdefault(jid, threading.Event()).set()
            self.db.execute("UPDATE archive_jobs SET status='pausing',updated_at=? WHERE id=? AND status='running'", (now(), jid))

    def resume(self, jid: str) -> None:
        job = self.get(jid)
        if not job:
            raise KeyError(jid)
        if job["status"] not in ("paused", "failed", "interrupted"):
            raise ValueError("Only paused, failed or interrupted jobs can be resumed")
        self.db.execute("UPDATE archive_jobs SET status='queued',error='',updated_at=? WHERE id=?", (now(), jid))
        self.start_worker()

    def reindex(self) -> dict:
        active = self.db.one("SELECT id FROM archive_jobs WHERE kind='reindex' AND status IN ('queued','running','pausing','paused','interrupted')")
        if active:
            return self.get(active["id"])
        jid, t = new_id(), now()
        self.db.execute("INSERT INTO archive_jobs(id,filename,file_path,kind,created_at,updated_at) VALUES (?,?,'','reindex',?,?)",
                        (jid, "Existing conversations", t, t))
        return self.get(jid)

    def delete(self, jid: str) -> None:
        job = self.db.one("SELECT * FROM archive_jobs WHERE id=?", (jid,))
        if not job:
            raise KeyError(jid)
        if job["status"] in ("queued", "running", "pausing"):
            raise ValueError("Pause the job before removing its retained upload")
        if job["file_path"]:
            Path(job["file_path"]).unlink(missing_ok=True)
        self.db.execute("DELETE FROM archive_jobs WHERE id=?", (jid,))

    def run(self, jid: str, pause: threading.Event | None = None) -> None:
        with self._job_lock(jid) as acquired:
            if acquired:
                self._run_locked(jid, pause)

    def _run_locked(self, jid: str, pause: threading.Event | None = None) -> None:
        pause = pause or threading.Event()
        with self.db.transaction() as conn:
            claimed = conn.execute("UPDATE archive_jobs SET status='running',error='',updated_at=? WHERE id=? AND status IN ('queued','paused','failed','interrupted')",
                                   (now(), jid)).rowcount
            if not claimed:
                return
            job = dict(conn.execute("SELECT * FROM archive_jobs WHERE id=?", (jid,)).fetchone())
        try:
            if job["kind"] == "reindex":
                self._reindex(job, pause)
            else:
                self._import(job, pause)
            if self._pause_requested(jid):
                pause.set()
            if pause.is_set():
                status = "paused"
            else:
                status = "done"
                if job["file_path"]:
                    Path(job["file_path"]).unlink(missing_ok=True)
            self.db.execute("UPDATE archive_jobs SET status=?,updated_at=? WHERE id=?", (status, now(), jid))
        except Exception as exc:
            self.db.execute("UPDATE archive_jobs SET status='failed',error=?,updated_at=? WHERE id=?",
                            (f"{exc.__class__.__name__}: {exc}"[:1500], now(), jid))
        finally:
            self.db.close_thread()

    def _import(self, job: dict, pause: threading.Event) -> None:
        batch_size = max(1, min(int(self.settings["archive"]["batch_size"]), 2000))
        cache: OrderedDict = OrderedDict()
        batch = []
        maximum = int(float(self.settings["archive"]["max_import_gb"]) * 1024**3)
        for ordinal, record in enumerate(archive_records(Path(job["file_path"]), job["filename"], job["file_hash"], maximum), 1):
            if pause.is_set():
                break
            if ordinal <= job["checkpoint"]:
                continue
            batch.append(record)
            if len(batch) >= batch_size:
                self._write_batch(job, batch, ordinal, cache)
                batch.clear()
                if self._pause_requested(job["id"]):
                    pause.set()
        if batch:
            self._write_batch(job, batch, job["checkpoint"] + len(batch), cache)
        if not pause.is_set() and not (job["inserted"] + job["updated"] + job["duplicates"]):
            raise ValueError("No conversations/messages found. Use the standard importer for projects or memory lists.")

    def _write_batch(self, job: dict, batch: list, checkpoint: int, cache: OrderedDict) -> None:
        counts = {key: 0 for key in ("inserted", "updated", "duplicates", "skipped")}
        changed_conversations: dict[str, float] = {}
        with self.db.transaction() as conn:
            for record in batch:
                if record is None:
                    counts["skipped"] += 1
                    continue
                key = (record["source"], record["conversation"])
                cached = cache.get(key)
                if cached is None:
                    conv = conn.execute("SELECT id,project_id FROM conversations WHERE source=? AND source_id=?", key).fetchone()
                    if conv:
                        if conv["project_id"] != job["project_id"]:
                            raise ValueError("A conversation already belongs to a different project; import into its original scope")
                        cid = conv["id"]
                        seq = conn.execute("SELECT COALESCE(MAX(seq),-1)+1 FROM messages WHERE conversation_id=?", (cid,)).fetchone()[0]
                        first = conn.execute("SELECT meta FROM messages WHERE conversation_id=? ORDER BY seq LIMIT 1", (cid,)).fetchone()
                        legacy = first is not None and "source_message_id" not in json.loads(first["meta"])
                    else:
                        cid, seq, legacy = new_id(), 0, False
                        conn.execute(
                            "INSERT INTO conversations(id,title,project_id,source,source_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                            (cid, record["title"], job["project_id"], *key, _timestamp(record.get("created"), job["created_at"]),
                             _timestamp(record.get("updated"), record.get("created"), job["created_at"])))
                    cached = [cid, seq, legacy]
                    cache[key] = cached
                    if len(cache) > 256:
                        cache.popitem(last=False)
                else:
                    cache.move_to_end(key)
                cid, seq, legacy = cached
                mid = stable_id(*key, record["id"])
                stamp = _timestamp(record.get("time"), record.get("created"), job["created_at"])
                meta = json.dumps({"source_message_id": record["id"], "source_time": record.get("time"), "archive_import": True})
                if legacy and record.get("source_seq") is not None:
                    # Adopt matching pre-upgrade exports without replacing their IDs.
                    old = conn.execute("SELECT id FROM messages WHERE conversation_id=? AND seq=? AND role=? AND content=?",
                                       (cid, record["source_seq"], record["role"], record["content"])).fetchone()
                    if old:
                        conn.execute("UPDATE messages SET meta=?,created_at=? WHERE id=?", (meta, stamp, old["id"]))
                        index_message(conn, old["id"], record["content"])
                result = conn.execute(
                    "INSERT OR IGNORE INTO messages(id,conversation_id,seq,role,content,meta,created_at) VALUES (?,?,?,?,?,?,?)",
                    (mid, cid, seq, record["role"], record["content"], meta, stamp))
                if result.rowcount:
                    counts["inserted"] += 1
                    cached[1] += 1
                else:
                    old = conn.execute("SELECT id,content,role,created_at,meta FROM messages WHERE conversation_id=? AND json_extract(meta,'$.source_message_id')=?",
                                       (cid, record["id"])).fetchone()
                    if old is None:
                        raise ValueError("A message could not be stored")
                    mid = old["id"]
                    old_meta = json.loads(old["meta"])
                    timestamp_changed = record.get("time") is not None and old_meta.get("source_time") != record["time"]
                    if old["content"] == record["content"] and old["role"] == record["role"] and not timestamp_changed:
                        counts["duplicates"] += 1
                        continue
                    if record.get("time") is None:
                        stamp = old["created_at"]
                        meta = json.dumps({**old_meta, "source_message_id": record["id"], "archive_import": True})
                    conn.execute("UPDATE messages SET content=?,role=?,meta=?,created_at=? WHERE id=?",
                                 (record["content"], record["role"], meta, stamp, mid))
                    counts["updated"] += 1
                index_history_message(conn, mid, cid, record["content"], replace=not result.rowcount)
                index_message(conn, mid, record["content"])
                changed_conversations[cid] = max(changed_conversations.get(cid, stamp), stamp)
            conn.executemany("UPDATE conversations SET updated_at=MAX(updated_at,?) WHERE id=?",
                             [(stamp, cid) for cid, stamp in changed_conversations.items()])
            conn.execute(
                "UPDATE archive_jobs SET checkpoint=?,processed=processed+?,inserted=inserted+?,updated=updated+?,"
                "duplicates=duplicates+?,skipped=skipped+?,updated_at=? WHERE id=?",
                (checkpoint, len(batch), counts["inserted"], counts["updated"], counts["duplicates"], counts["skipped"], now(), job["id"]))
        job["checkpoint"] = checkpoint
        job["processed"] += len(batch)
        for key, value in counts.items():
            job[key] += value
        if self.progress:
            self.progress(self.get(job["id"]))

    def _reindex(self, job: dict, pause: threading.Event) -> None:
        cursor = job["checkpoint"]
        if cursor == 0:
            with self.db.transaction() as conn:
                conn.execute("INSERT OR IGNORE INTO message_fts_rows(message_id,fts_rowid) "
                             "SELECT f.message_id,f.rowid FROM messages_fts f JOIN messages m ON m.id=f.message_id")
        while not pause.is_set():
            rows = self.db.all("SELECT rowid cursor,id,content FROM messages WHERE rowid>? ORDER BY rowid LIMIT 250", (cursor,))
            if not rows:
                break
            with self.db.transaction() as conn:
                for row in rows:
                    index_message(conn, row["id"], row["content"])
                cursor = rows[-1]["cursor"]
                conn.execute("UPDATE archive_jobs SET checkpoint=?,processed=processed+?,updated_at=? WHERE id=?",
                             (cursor, len(rows), now(), job["id"]))
            if self.progress:
                self.progress(self.get(job["id"]))
            if self._pause_requested(job["id"]):
                pause.set()

    def _pause_requested(self, jid: str) -> bool:
        job = self.db.one("SELECT status FROM archive_jobs WHERE id=?", (jid,))
        return job is None or job["status"] == "pausing"


def main() -> None:
    """Local-file CLI avoids uploading multi-GB datasets through a browser."""
    import argparse
    import signal
    from .config import Settings

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?", type=Path)
    parser.add_argument("--data-dir")
    parser.add_argument("--project", help="Existing project ID or name")
    parser.add_argument("--resume", metavar="JOB_ID")
    args = parser.parse_args()
    if bool(args.file) == bool(args.resume):
        parser.error("Provide either a file or --resume JOB_ID")
    settings = Settings(args.data_dir)
    db = Database(settings.db_path)
    def progress(job):
        print(f"{job['id']}: {job['processed']:,} processed; {job['inserted']:,} added; "
              f"{job['updated']:,} updated; {job['duplicates']:,} duplicates", flush=True)
    bulk = BulkImports(db, settings, progress)
    if args.resume:
        bulk.recover()
        job = bulk.get(args.resume)
        if not job or job["status"] not in ("paused", "failed", "interrupted"):
            parser.error("Resume requires a paused, failed or interrupted job")
    else:
        project_id = None
        if args.project:
            project = db.one("SELECT id FROM projects WHERE id=? OR lower(name)=lower(?)", (args.project, args.project))
            if not project:
                parser.error("Project not found; create it in KYRO first")
            project_id = project["id"]
        try:
            with args.file.open("rb") as source:
                job = bulk.stage(source, args.file.name, project_id, initial_status="paused")
        except (OSError, ValueError) as exc:
            parser.error(str(exc))
    pause = threading.Event()
    previous = signal.signal(signal.SIGINT, lambda *_: pause.set())
    print(f"Job {job['id']}; Ctrl+C pauses after the current batch.", flush=True)
    try:
        bulk.run(job["id"], pause)
    finally:
        signal.signal(signal.SIGINT, previous)
    result = bulk.get(job["id"])
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result["status"] == "failed":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
