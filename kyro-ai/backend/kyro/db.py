"""SQLite persistence. One file, WAL mode, FTS5 for full-text search over history and memory."""
from __future__ import annotations

import json
import hashlib
import sqlite3
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS projects (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL DEFAULT '',
  instructions TEXT NOT NULL DEFAULT '',
  workspace_path TEXT NOT NULL DEFAULT '',
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT 'New chat',
  project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
  mode TEXT NOT NULL DEFAULT 'chat',
  model TEXT NOT NULL DEFAULT 'auto',
  personality TEXT NOT NULL DEFAULT '',
  summary TEXT NOT NULL DEFAULT '',
  summary_upto INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL DEFAULT 'kyro',      -- kyro | chatgpt | claude
  source_id TEXT NOT NULL DEFAULT '',
  pinned INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conv_updated ON conversations(updated_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_conv_source ON conversations(source, source_id) WHERE source_id != '';

CREATE TABLE IF NOT EXISTS messages (
  id TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  role TEXT NOT NULL,                       -- user | assistant | system | tool
  content TEXT NOT NULL,
  model TEXT NOT NULL DEFAULT '',
  attachments TEXT NOT NULL DEFAULT '[]',   -- JSON list of attachment ids
  meta TEXT NOT NULL DEFAULT '{}',          -- JSON: citations, memories used, timings
  created_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id, seq);
CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_source ON messages(conversation_id, json_extract(meta, '$.source_message_id'))
  WHERE json_extract(meta, '$.source_message_id') IS NOT NULL;

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(content, conversation_id UNINDEXED, message_id UNINDEXED);
CREATE TABLE IF NOT EXISTS message_fts_rows (
  message_id TEXT PRIMARY KEY REFERENCES messages(id) ON DELETE CASCADE,
  fts_rowid INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS memories (
  id TEXT PRIMARY KEY,
  content TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'fact',        -- fact | preference | instruction | project | note | document
  scope TEXT NOT NULL DEFAULT 'general',    -- general | project
  project_id TEXT REFERENCES projects(id) ON DELETE CASCADE,
  source TEXT NOT NULL DEFAULT 'manual',    -- manual | chatgpt | claude | chatgpt-memory | claude-project | note | document | kyro-chat | seed
  source_ref TEXT NOT NULL DEFAULT '',      -- conversation id / file name / message id
  source_time REAL,                         -- original timestamp from the source, if known
  content_hash TEXT NOT NULL,
  embedding BLOB,
  embedding_model TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'active',    -- active | superseded | conflict | archived
  superseded_by TEXT,
  conflicts_with TEXT NOT NULL DEFAULT '[]',
  extra_sources TEXT NOT NULL DEFAULT '[]', -- other sources that stated the same thing (dedup merges)
  verified INTEGER NOT NULL DEFAULT 0,
  pinned INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mem_scope ON memories(scope, project_id, status);
CREATE INDEX IF NOT EXISTS idx_mem_hash ON memories(content_hash);

CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(content, memory_id UNINDEXED);

CREATE TABLE IF NOT EXISTS attachments (
  id TEXT PRIMARY KEY,
  filename TEXT NOT NULL,
  stored_path TEXT NOT NULL,
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  kind TEXT NOT NULL,                       -- image | pdf | text | code | zip | jar | document | other
  extracted_text TEXT NOT NULL DEFAULT '',
  analysis TEXT NOT NULL DEFAULT '{}',
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS document_chunks (
  id TEXT PRIMARY KEY,
  attachment_id TEXT NOT NULL REFERENCES attachments(id) ON DELETE CASCADE,
  project_id TEXT,
  idx INTEGER NOT NULL,
  content TEXT NOT NULL,
  embedding BLOB,
  embedding_model TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS imports (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  filename TEXT NOT NULL,
  status TEXT NOT NULL,
  stats TEXT NOT NULL DEFAULT '{}',
  error TEXT NOT NULL DEFAULT '',
  created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_runs (
  id TEXT PRIMARY KEY,
  conversation_id TEXT,
  project_id TEXT,
  task TEXT NOT NULL,
  status TEXT NOT NULL,                     -- running | waiting_approval | done | failed | cancelled
  phase TEXT NOT NULL DEFAULT 'PLAN',
  workspace TEXT NOT NULL,
  events TEXT NOT NULL DEFAULT '[]',
  verification TEXT NOT NULL DEFAULT '{}',
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts REAL NOT NULL,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT ''
);

-- Derived archive index. Original messages and their source metadata remain authoritative.
CREATE TABLE IF NOT EXISTS archive_chunks (
  id INTEGER PRIMARY KEY,
  message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  idx INTEGER NOT NULL,
  content TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  UNIQUE(message_id, idx)
);
CREATE VIRTUAL TABLE IF NOT EXISTS archive_fts USING fts5(
  content, content='archive_chunks', content_rowid='id', tokenize='unicode61'
);
CREATE TRIGGER IF NOT EXISTS archive_insert AFTER INSERT ON archive_chunks BEGIN
  INSERT INTO archive_fts(rowid, content) VALUES (new.id, new.content);
END;
CREATE TRIGGER IF NOT EXISTS archive_delete AFTER DELETE ON archive_chunks BEGIN
  INSERT INTO archive_fts(archive_fts, rowid, content) VALUES ('delete', old.id, old.content);
END;
CREATE TRIGGER IF NOT EXISTS archive_update AFTER UPDATE OF content ON archive_chunks BEGIN
  INSERT INTO archive_fts(archive_fts, rowid, content) VALUES ('delete', old.id, old.content);
  INSERT INTO archive_fts(rowid, content) VALUES (new.id, new.content);
END;
CREATE TABLE IF NOT EXISTS archive_embeddings (
  chunk_id INTEGER NOT NULL REFERENCES archive_chunks(id) ON DELETE CASCADE,
  model TEXT NOT NULL,
  embedding BLOB NOT NULL,
  PRIMARY KEY(chunk_id, model)
);
CREATE TABLE IF NOT EXISTS archive_jobs (
  id TEXT PRIMARY KEY,
  filename TEXT NOT NULL,
  file_path TEXT NOT NULL,
  file_hash TEXT NOT NULL DEFAULT '',
  project_id TEXT REFERENCES projects(id) ON DELETE CASCADE,
  kind TEXT NOT NULL DEFAULT 'import',
  status TEXT NOT NULL DEFAULT 'queued',
  checkpoint INTEGER NOT NULL DEFAULT 0,
  processed INTEGER NOT NULL DEFAULT 0,
  inserted INTEGER NOT NULL DEFAULT 0,
  updated INTEGER NOT NULL DEFAULT 0,
  duplicates INTEGER NOT NULL DEFAULT 0,
  skipped INTEGER NOT NULL DEFAULT 0,
  error TEXT NOT NULL DEFAULT '',
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
"""


def new_id() -> str:
    return uuid.uuid4().hex


def stable_id(*parts) -> str:
    return hashlib.sha256(json.dumps(parts, ensure_ascii=False, default=str).encode()).hexdigest()[:32]


def now() -> float:
    return time.time()


def index_history_message(conn: sqlite3.Connection, message_id: str, cid: str, content: str, *, replace: bool = False) -> None:
    """Update FTS by rowid; repeated large imports must not scan all FTS rows per edit."""
    if replace:
        old = conn.execute("SELECT fts_rowid FROM message_fts_rows WHERE message_id=?", (message_id,)).fetchone()
        if old:
            conn.execute("DELETE FROM messages_fts WHERE rowid=?", (old[0],))
        else:  # legacy rows are mapped by the explicit reindex job
            conn.execute("DELETE FROM messages_fts WHERE message_id=?", (message_id,))
    rowid = conn.execute("INSERT INTO messages_fts(content,conversation_id,message_id) VALUES (?,?,?)",
                         (content, cid, message_id)).lastrowid
    conn.execute("INSERT INTO message_fts_rows(message_id,fts_rowid) VALUES (?,?) "
                 "ON CONFLICT(message_id) DO UPDATE SET fts_rowid=excluded.fts_rowid", (message_id, rowid))


class Database:
    """Thread-safe wrapper. SQLite connections are per-thread; writes are serialised."""

    def __init__(self, path: str | Path):
        self.path = str(path)
        self._local = threading.local()
        self._write_lock = threading.RLock()
        with self._write_lock:
            self.conn.executescript(SCHEMA)

    @property
    def conn(self) -> sqlite3.Connection:
        c = getattr(self._local, "conn", None)
        if c is None:
            c = sqlite3.connect(self.path, check_same_thread=False, timeout=30)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA foreign_keys=ON")
            self._local.conn = c
        return c

    def execute(self, sql: str, params: Iterable[Any] = ()) -> sqlite3.Cursor:
        with self._write_lock:
            cur = self.conn.execute(sql, tuple(params))
            self.conn.commit()
            return cur

    def executemany(self, sql: str, rows: Iterable[Iterable[Any]]) -> None:
        with self._write_lock:
            self.conn.executemany(sql, rows)
            self.conn.commit()

    def transaction(self):
        return _Tx(self)

    def one(self, sql: str, params: Iterable[Any] = ()) -> dict | None:
        r = self.conn.execute(sql, tuple(params)).fetchone()
        return dict(r) if r else None

    def all(self, sql: str, params: Iterable[Any] = ()) -> list[dict]:
        return [dict(r) for r in self.conn.execute(sql, tuple(params)).fetchall()]

    def close_thread(self) -> None:
        """Release a worker thread's connection after a background job."""
        c = getattr(self._local, "conn", None)
        if c is not None:
            c.close()
            del self._local.conn

    def audit(self, actor: str, action: str, detail: Any = "") -> None:
        if not isinstance(detail, str):
            detail = json.dumps(detail, default=str)[:4000]
        self.execute("INSERT INTO audit_log(ts, actor, action, detail) VALUES (?,?,?,?)", (now(), actor, action, detail))


class _Tx:
    def __init__(self, db: Database):
        self.db = db

    def __enter__(self) -> sqlite3.Connection:
        self.db._write_lock.acquire()
        return self.db.conn

    def __exit__(self, exc_type, exc, tb):
        try:
            if exc_type is None:
                self.db.conn.commit()
            else:
                self.db.conn.rollback()
        finally:
            self.db._write_lock.release()


def fts_query(text: str) -> str:
    """Turn free text into a safe FTS5 query (prefix OR match on word tokens)."""
    import re

    toks = [t for t in re.findall(r"[\w\-\.]{2,}", text.lower()) if t not in _STOP][:12]
    toks = [t.replace('"', "") for t in toks]
    return " OR ".join(f'"{t}"*' for t in toks) if toks else ""


_STOP = set(
    "the a an and or but if of to in on for with is are was were be been it this that as at by from "
    "i you he she we they me my your our their what which who how why when where can could would "
    "should do does did not no yes so than then there here have has had will just about into "
    "der die das den dem des ein eine einer einem einen und oder aber ich du er sie wir ihr "
    "ist sind war waren wie was warum wo wann mit von für auf zu im in am an bitte "
    "kann kannst können könntest mir mich meine mein deinem deine unser unsere "
    "habe haben hat hatte welche welcher welches über aus bei nach noch auch".split()
)
