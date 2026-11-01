"""Persistent conversation chunks, scoped FTS retrieval and bounded semantic reranking.

Importing does not require Ollama. Only a bounded set of keyword candidates is
embedded on demand; cached vectors survive restarts. No full vector scan is used.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import sqlite3

from .db import Database, fts_query
from .embeddings import FALLBACK_MODEL, Embedder, chunk_text, cosine, from_blob, to_blob


def index_message(conn: sqlite3.Connection, message_id: str, content: str) -> int:
    """Maintain derived chunks in the caller's transaction. Unchanged chunks keep vectors."""
    chunks = chunk_text(content, size=1800, overlap=180)
    existing = {r["idx"]: r for r in conn.execute(
        "SELECT id,idx,content_hash FROM archive_chunks WHERE message_id=?", (message_id,))}
    for idx, text in enumerate(chunks):
        digest = hashlib.sha256(text.encode()).hexdigest()
        old = existing.pop(idx, None)
        if old and old["content_hash"] == digest:
            continue
        if old:
            conn.execute("DELETE FROM archive_chunks WHERE id=?", (old["id"],))
        conn.execute("INSERT INTO archive_chunks(message_id,idx,content,content_hash) VALUES (?,?,?,?)",
                     (message_id, idx, text, digest))
    for old in existing.values():
        conn.execute("DELETE FROM archive_chunks WHERE id=?", (old["id"],))
    return len(chunks)


class ArchiveStore:
    def __init__(self, db: Database, embedder: Embedder, settings):
        self.db, self.embedder, self.settings = db, embedder, settings

    def _lexical(self, query: str, project_id: str | None, limit: int,
                 exclude_message_id: str | None) -> list[dict]:
        fq = fts_query(query)
        if not fq:
            return []
        scope, args = "c.project_id IS NULL", []
        if project_id:
            scope = "(c.project_id IS NULL OR c.project_id=?)"
            args.append(project_id)
        exclude = ""
        if exclude_message_id:
            exclude = " AND m.id!=?"
            args.append(exclude_message_id)
        return self.db.all(
            "SELECT a.id,a.message_id,a.idx,a.content,c.id conversation_id,c.title,c.source,"
            "c.source_id,c.project_id,m.role,m.seq,m.created_at source_time,m.meta,"
            "archive_fts.rank rank FROM archive_fts "
            "JOIN archive_chunks a ON a.id=archive_fts.rowid "
            "JOIN messages m ON m.id=a.message_id JOIN conversations c ON c.id=m.conversation_id "
            f"WHERE archive_fts MATCH ? AND {scope}{exclude} "
            "ORDER BY archive_fts.rank LIMIT ?", (fq, *args, limit))

    async def search(self, query: str, *, project_id: str | None = None,
                     top_k: int | None = None, exclude_message_id: str | None = None) -> list[dict]:
        cfg = self.settings["archive"]
        limit = max(1, min(int(cfg["candidate_limit"]), 128))
        top_k = max(1, min(int(top_k or cfg["top_k"]), 20))
        rows = await asyncio.to_thread(self._lexical, query, project_id, limit, exclude_message_id)
        if not rows:
            return []
        scores = {r["id"]: 1 / (60 + i) for i, r in enumerate(rows, 1)}
        retrieval = "fts5"
        if cfg["semantic_rerank"]:
            try:
                model = await self.embedder.active_model()
                if model != FALLBACK_MODEL:
                    # Version includes preprocessing: different index spaces never mix.
                    space = model + "|archive-v1"
                    marks = ",".join("?" for _ in rows)
                    cached = {r["chunk_id"]: from_blob(r["embedding"]) for r in self.db.all(
                        f"SELECT chunk_id,embedding FROM archive_embeddings WHERE model=? AND chunk_id IN ({marks})",
                        (space, *(r["id"] for r in rows)))}
                    missing = [r for r in rows if r["id"] not in cached]
                    is_nomic = model.split(":")[0] in ("nomic-embed-text", "nomic-embed-text-v1.5")
                    query_text = ("search_query: " if is_nomic else "") + query
                    texts = [query_text] + [("search_document: " if is_nomic else "") + r["content"] for r in missing]
                    vectors, actual = await self.embedder.embed(texts)
                    if actual == model and len(vectors) == len(texts):
                        q = vectors[0]
                        with self.db.transaction() as conn:
                            for r, vec in zip(missing, vectors[1:]):
                                # The source may have been removed while embedding was running.
                                conn.execute(
                                    "INSERT OR REPLACE INTO archive_embeddings(chunk_id,model,embedding) "
                                    "SELECT id,?,? FROM archive_chunks WHERE id=? AND content_hash=?",
                                    (space, to_blob(vec), r["id"], hashlib.sha256(r["content"].encode()).hexdigest()))
                                cached[r["id"]] = vec
                        ranked = sorted(rows, key=lambda r: cosine(q, cached.get(r["id"])), reverse=True)
                        for rank, r in enumerate(ranked, 1):
                            scores[r["id"]] += 1 / (60 + rank)
                        rows.sort(key=lambda r: scores[r["id"]], reverse=True)
                        retrieval = "fts5+semantic_rerank"
            except Exception:  # Ollama/cache failures must leave the lexical archive usable.
                pass
        out, per_message = [], {}
        for row in rows:
            mid = row["message_id"]
            if per_message.get(mid, 0) >= 2:
                continue
            per_message[mid] = per_message.get(mid, 0) + 1
            row.pop("rank", None)
            meta = json.loads(row.pop("meta") or "{}")
            if "source_time" in meta:
                row["source_time"] = meta["source_time"]
            row.update(score=round(scores[row["id"]], 6), retrieval=retrieval,
                       source_message_id=meta.get("source_message_id", ""),
                       url=f"/c/{row['conversation_id']}?message={mid}")
            out.append(row)
            if len(out) == top_k:
                break
        return out

    def stats(self) -> dict:
        return {
            "chunks": self.db.one("SELECT COUNT(*) n FROM archive_chunks")["n"],
            "cached_embeddings": self.db.one("SELECT COUNT(*) n FROM archive_embeddings")["n"],
        }
