"""Unified long-term memory: ChatGPT + Claude + notes + projects in one local SQLite store.

Guarantees
* Every memory keeps its original source, source reference and source timestamp.
* Exact and near-duplicates are merged (the extra source is recorded on the kept memory).
* Possible contradictions are flagged on both memories, never silently dropped. Retrieval
  prefers the newer one; the user resolves conflicts in the Memory page.
* Nothing is invented: extraction only stores text that is present in the source, and
  LLM-assisted extraction is grounding-checked against the source text before saving.
"""
from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import dataclass
from typing import Any

import numpy as np

from .db import Database, fts_query, new_id, now
from .embeddings import Embedder, cosine, from_blob, to_blob

KINDS = {"fact", "preference", "instruction", "project", "note", "document"}


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def content_hash(text: str) -> str:
    t = re.sub(r"[^\w]+", " ", text.lower()).strip()
    return hashlib.sha256(t.encode()).hexdigest()


_NEG = re.compile(r"\b(not|never|no longer|don't|dont|doesn't|isn't|aren't|won't|stopped|hate|dislike|avoid|nicht|kein|keine|nie)\b", re.I)
_NUM = re.compile(r"\b\d+(?:\.\d+)*\b")
_VALUE_VERBS = re.compile(r"\b(prefer|prefers|use|uses|using|is|am|are|switched to|moved to|runs on|target|targets|main language|favorite|favourite)\b", re.I)


def contradiction_signal(a: str, b: str) -> str | None:
    """Cheap, explainable contradiction heuristics for two similar statements."""
    na, nb = bool(_NEG.search(a)), bool(_NEG.search(b))
    if na != nb:
        return "negation differs"
    nums_a, nums_b = set(_NUM.findall(a)), set(_NUM.findall(b))
    if nums_a and nums_b and nums_a != nums_b:
        return f"different numbers/versions ({', '.join(sorted(nums_a))} vs {', '.join(sorted(nums_b))})"
    if _VALUE_VERBS.search(a) and _VALUE_VERBS.search(b):
        wa = set(re.findall(r"[a-z][a-z0-9+#.\-]{2,}", a.lower()))
        wb = set(re.findall(r"[a-z][a-z0-9+#.\-]{2,}", b.lower()))
        diff_a, diff_b = wa - wb, wb - wa
        if diff_a and diff_b and len(diff_a) <= 3 and len(diff_b) <= 3:
            return f"different values ({', '.join(sorted(diff_a))} vs {', '.join(sorted(diff_b))})"
    return None


@dataclass
class AddResult:
    status: str  # created | duplicate | conflict
    id: str
    duplicate_of: str | None = None
    conflicts: list[dict] | None = None

    def dict(self) -> dict:
        return self.__dict__


def row_out(r: dict) -> dict:
    r = dict(r)
    r.pop("embedding", None)
    for k in ("conflicts_with", "extra_sources"):
        r[k] = json.loads(r.get(k) or "[]")
    r["verified"] = bool(r.get("verified"))
    r["pinned"] = bool(r.get("pinned"))
    return r


class MemoryStore:
    def __init__(self, db: Database, embedder: Embedder, settings):
        self.db = db
        self.embedder = embedder
        self.settings = settings

    @property
    def cfg(self) -> dict:
        return self.settings["memory"]

    # ------------------------------------------------------------------ write
    async def add(self, content: str, *, kind: str = "fact", scope: str = "general", project_id: str | None = None,
                  source: str = "manual", source_ref: str = "", source_time: float | None = None,
                  verified: bool = False, check_duplicates: bool = True) -> AddResult:
        content = normalize(content)
        if not content:
            raise ValueError("empty memory")
        if kind not in KINDS:
            kind = "fact"
        if scope == "project" and not project_id:
            raise ValueError("project-scoped memory needs a project_id")
        if scope != "project":
            project_id = None
        h = content_hash(content)
        src_entry = {"source": source, "ref": source_ref, "time": source_time}

        scope_sql, scope_args = self._scope_filter(project_id, include_general=False, exact=True)
        if check_duplicates:
            same = self.db.one(f"SELECT * FROM memories WHERE content_hash=? AND status!='archived' AND {scope_sql}", (h, *scope_args))
            if same:
                self._merge_source(same, src_entry)
                return AddResult("duplicate", same["id"], duplicate_of=same["id"])

        vec, model = await self.embedder.embed_one(content)
        conflicts: list[dict] = []
        if check_duplicates:
            for cand in self._candidates(model, project_id, exact=True):
                sim = cosine(vec, from_blob(cand["embedding"]))
                if sim >= self.cfg["duplicate_threshold"] and not contradiction_signal(content, cand["content"]):
                    self._merge_source(cand, src_entry)
                    return AddResult("duplicate", cand["id"], duplicate_of=cand["id"])
                if sim >= self.cfg["conflict_threshold"]:
                    why = contradiction_signal(content, cand["content"])
                    if why:
                        conflicts.append({"id": cand["id"], "reason": why, "similarity": round(sim, 3),
                                          "content": cand["content"], "source_time": cand["source_time"]})

        mid = new_id()
        t = now()
        with self.db.transaction() as c:
            c.execute(
                "INSERT INTO memories(id,content,kind,scope,project_id,source,source_ref,source_time,content_hash,embedding,"
                "embedding_model,status,conflicts_with,verified,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (mid, content, kind, scope, project_id, source, source_ref, source_time, h, to_blob(vec), model,
                 "conflict" if conflicts else "active", json.dumps([x["id"] for x in conflicts]), int(verified), t, t))
            c.execute("INSERT INTO memories_fts(content, memory_id) VALUES (?,?)", (content, mid))
            for x in conflicts:
                old = c.execute("SELECT conflicts_with FROM memories WHERE id=?", (x["id"],)).fetchone()
                lst = json.loads(old[0] or "[]") + [mid]
                c.execute("UPDATE memories SET conflicts_with=?, status=CASE WHEN status='active' THEN 'conflict' ELSE status END, updated_at=? WHERE id=?",
                          (json.dumps(lst), t, x["id"]))
        return AddResult("conflict" if conflicts else "created", mid, conflicts=conflicts or None)

    def _merge_source(self, row: dict, src: dict) -> None:
        extra = json.loads(row.get("extra_sources") or "[]")
        if src not in extra and not (src["source"] == row["source"] and src["ref"] == row["source_ref"]):
            extra.append(src)
            self.db.execute("UPDATE memories SET extra_sources=?, updated_at=? WHERE id=?", (json.dumps(extra), now(), row["id"]))

    async def update(self, mid: str, patch: dict) -> dict:
        row = self.db.one("SELECT * FROM memories WHERE id=?", (mid,))
        if not row:
            raise KeyError(mid)
        fields: dict[str, Any] = {}
        for k in ("kind", "status", "verified", "pinned"):
            if k in patch:
                fields[k] = int(patch[k]) if k in ("verified", "pinned") else patch[k]
        if "scope" in patch or "project_id" in patch:
            scope = patch.get("scope", row["scope"])
            pid = patch.get("project_id", row["project_id"]) if scope == "project" else None
            if scope == "project" and not pid:
                raise ValueError("project-scoped memory needs a project_id")
            fields.update(scope=scope, project_id=pid)
        if "content" in patch and normalize(patch["content"]) != row["content"]:
            content = normalize(patch["content"])
            vec, model = await self.embedder.embed_one(content)
            fields.update(content=content, content_hash=content_hash(content), embedding=to_blob(vec), embedding_model=model)
        if not fields:
            return row_out(row)
        fields["updated_at"] = now()
        sets = ", ".join(f"{k}=?" for k in fields)
        with self.db.transaction() as c:
            c.execute(f"UPDATE memories SET {sets} WHERE id=?", (*fields.values(), mid))
            if "content" in fields:
                c.execute("DELETE FROM memories_fts WHERE memory_id=?", (mid,))
                c.execute("INSERT INTO memories_fts(content, memory_id) VALUES (?,?)", (fields["content"], mid))
        self.db.audit("user", "memory.update", {"id": mid, "fields": [k for k in fields if k != "embedding"]})
        return self.get(mid)

    def delete(self, mid: str) -> bool:
        with self.db.transaction() as c:
            n = c.execute("DELETE FROM memories WHERE id=?", (mid,)).rowcount
            c.execute("DELETE FROM memories_fts WHERE memory_id=?", (mid,))
            for r in c.execute("SELECT id, conflicts_with FROM memories WHERE conflicts_with LIKE ?", (f"%{mid}%",)).fetchall():
                lst = [x for x in json.loads(r[1]) if x != mid]
                c.execute("UPDATE memories SET conflicts_with=?, status=CASE WHEN status='conflict' AND ?=0 THEN 'active' ELSE status END WHERE id=?",
                          (json.dumps(lst), len(lst), r[0]))
        self.db.audit("user", "memory.delete", mid)
        return n > 0

    def resolve_conflict(self, keep_id: str, other_id: str, action: str) -> dict:
        """action: keep_one (other becomes superseded) | keep_both (clear the flag)."""
        keep, other = self.get(keep_id), self.get(other_id)
        if not keep or not other:
            raise KeyError("memory not found")
        t = now()
        with self.db.transaction() as c:
            for a, b in ((keep, other), (other, keep)):
                lst = [x for x in a["conflicts_with"] if x != b["id"]]
                c.execute("UPDATE memories SET conflicts_with=?, updated_at=? WHERE id=?", (json.dumps(lst), t, a["id"]))
            if action == "keep_one":
                c.execute("UPDATE memories SET status='superseded', superseded_by=? WHERE id=?", (keep_id, other_id))
                c.execute("UPDATE memories SET status='active', verified=1 WHERE id=? AND conflicts_with='[]'", (keep_id,))
            else:
                c.execute("UPDATE memories SET status='active' WHERE id IN (?,?) AND conflicts_with='[]' AND status='conflict'", (keep_id, other_id))
        self.db.audit("user", "memory.resolve", {"keep": keep_id, "other": other_id, "action": action})
        return {"keep": self.get(keep_id), "other": self.get(other_id)}

    # ------------------------------------------------------------------ read
    def get(self, mid: str) -> dict | None:
        r = self.db.one("SELECT * FROM memories WHERE id=?", (mid,))
        return row_out(r) if r else None

    def list(self, *, scope: str | None = None, project_id: str | None = None, status: str | None = None,
             source: str | None = None, kind: str | None = None, q: str | None = None, limit: int = 200, offset: int = 0) -> dict:
        where, args = ["1=1"], []
        if scope:
            where.append("scope=?"); args.append(scope)
        if project_id:
            where.append("project_id=?"); args.append(project_id)
        if status:
            where.append("status=?"); args.append(status)
        if source:
            where.append("source=?"); args.append(source)
        if kind:
            where.append("kind=?"); args.append(kind)
        if q:
            where.append("content LIKE ?"); args.append(f"%{q}%")
        w = " AND ".join(where)
        total = self.db.one(f"SELECT COUNT(*) n FROM memories WHERE {w}", args)["n"]
        rows = self.db.all(f"SELECT * FROM memories WHERE {w} ORDER BY pinned DESC, COALESCE(source_time, created_at) DESC LIMIT ? OFFSET ?", (*args, limit, offset))
        return {"total": total, "items": [row_out(r) for r in rows]}

    def stats(self) -> dict:
        return {
            "total": self.db.one("SELECT COUNT(*) n FROM memories")["n"],
            "by_source": {r["source"]: r["n"] for r in self.db.all("SELECT source, COUNT(*) n FROM memories GROUP BY source")},
            "by_status": {r["status"]: r["n"] for r in self.db.all("SELECT status, COUNT(*) n FROM memories GROUP BY status")},
            "by_kind": {r["kind"]: r["n"] for r in self.db.all("SELECT kind, COUNT(*) n FROM memories GROUP BY kind")},
            "embedding_models": {r["embedding_model"]: r["n"] for r in self.db.all("SELECT embedding_model, COUNT(*) n FROM memories GROUP BY embedding_model")},
        }

    def _scope_filter(self, project_id: str | None, include_general: bool = True, exact: bool = False) -> tuple[str, tuple]:
        if project_id:
            if exact or not include_general:
                return "(scope='project' AND project_id=?)", (project_id,)
            return "(scope='general' OR (scope='project' AND project_id=?))", (project_id,)
        return "scope='general'", ()

    def _candidates(self, model: str, project_id: str | None, exact: bool = False, include_general: bool = True) -> list[dict]:
        sql, args = self._scope_filter(project_id, include_general, exact)
        return self.db.all(
            f"SELECT id, content, kind, scope, project_id, source, source_ref, source_time, embedding, status, conflicts_with, "
            f"verified, pinned, created_at, extra_sources FROM memories WHERE embedding_model=? AND status IN ('active','conflict') AND {sql}",
            (model, *args))

    async def search(self, query: str, *, project_id: str | None = None, include_general: bool = True,
                     top_k: int | None = None, min_score: float | None = None) -> list[dict]:
        """Hybrid retrieval: cosine similarity on embeddings + FTS5 keyword rank, small recency/verified boost."""
        top_k = top_k or self.cfg["top_k"]
        min_score = self.cfg["min_score"] if min_score is None else min_score
        if not query.strip():
            return []
        vec, model = await self.embedder.embed_one(query)
        cands = self._candidates(model, project_id, include_general=include_general)
        if not cands:
            return []
        fts: dict[str, float] = {}
        fq = fts_query(query)
        if fq:
            try:
                rows = self.db.all("SELECT memory_id, bm25(memories_fts) r FROM memories_fts WHERE memories_fts MATCH ? ORDER BY r LIMIT 200", (fq,))
                if rows:
                    best = min(r["r"] for r in rows)
                    fts = {r["memory_id"]: (r["r"] / best if best else 0.0) for r in rows}
            except Exception:  # noqa: BLE001 - malformed FTS query should never break chat
                fts = {}
        mat = np.stack([from_blob(c["embedding"]) for c in cands])
        qn = vec / (np.linalg.norm(vec) or 1)
        sims = mat @ qn / np.clip(np.linalg.norm(mat, axis=1), 1e-9, None)
        newest = max((c["source_time"] or c["created_at"]) for c in cands)
        scored = []
        for c, s in zip(cands, sims):
            kw = fts.get(c["id"], 0.0)
            score = 0.78 * float(s) + 0.22 * kw
            age_days = (newest - (c["source_time"] or c["created_at"])) / 86400
            score += 0.03 * max(0.0, 1 - age_days / 365) + (0.03 if c["verified"] else 0) + (0.05 if c["pinned"] else 0)
            if c["scope"] == "project":
                score += 0.04
            if score >= min_score or kw > 0.6:
                scored.append((score, float(s), kw, c))
        scored.sort(key=lambda x: x[0], reverse=True)
        out, seen_conflicts = [], set()
        for score, s, kw, c in scored:
            if c["id"] in seen_conflicts:
                continue
            item = row_out(c)
            item.update(score=round(score, 4), similarity=round(s, 4), keyword=round(kw, 3), retrieval_model=model)
            if item["conflicts_with"]:
                # Prefer the newest of a conflicting set; tell the model the others exist.
                others = [self.get(x) for x in item["conflicts_with"]]
                others = [o for o in others if o]
                for o in others:
                    seen_conflicts.add(o["id"])
                item["conflict_note"] = [{"id": o["id"], "content": o["content"], "source_time": o["source_time"]} for o in others]
            out.append(item)
            if len(out) >= top_k:
                break
        return out

    def export(self, fmt: str = "json", **filters) -> str:
        items = self.list(limit=10_000_000, **filters)["items"]
        if fmt == "md":
            lines = ["# KYRO AI memory export", f"_Exported {time.strftime('%Y-%m-%d %H:%M')}, {len(items)} memories_", ""]
            for m in items:
                when = time.strftime("%Y-%m-%d", time.localtime(m["source_time"])) if m["source_time"] else "unknown date"
                lines.append(f"- **[{m['kind']}/{m['scope']}]** {m['content']}  \n  _source: {m['source']} {m['source_ref']} ({when}), status: {m['status']}_")
            return "\n".join(lines)
        return json.dumps({"version": 1, "exported_at": now(), "memories": items}, indent=2, default=str)

    async def import_kyro_export(self, data: dict) -> dict:
        stats = {"created": 0, "duplicate": 0, "conflict": 0}
        for m in data.get("memories", []):
            pid = m.get("project_id")
            if pid and not self.db.one("SELECT id FROM projects WHERE id=?", (pid,)):
                pid = None
            r = await self.add(m["content"], kind=m.get("kind", "fact"), scope="project" if pid else "general", project_id=pid,
                               source=m.get("source", "kyro-export"), source_ref=m.get("source_ref", ""),
                               source_time=m.get("source_time"), verified=bool(m.get("verified")))
            stats[r.status] += 1
        return stats

    async def reembed_all(self) -> dict:
        self.embedder.reset()
        rows = self.db.all("SELECT id, content FROM memories")
        model = None
        for i in range(0, len(rows), 64):
            batch = rows[i:i + 64]
            vecs, model = await self.embedder.embed([r["content"] for r in batch])
            self.db.executemany("UPDATE memories SET embedding=?, embedding_model=? WHERE id=?",
                                [(to_blob(v), model, r["id"]) for v, r in zip(vecs, batch)])
        return {"reembedded": len(rows), "model": model}


# ---------------------------------------------------------------------- extraction
_PATTERNS = [
    (r"\b(my name is|call me|i am called|ich heiße|ich heisse)\b", "fact"),
    (r"\b(i prefer|i'd prefer|i like|i love|i don't like|i do not like|i hate|i want you to|please always|please never|always use|never use|ich bevorzuge|ich mag)\b", "preference"),
    (r"\b(remember that|please remember|keep in mind|note that|merke dir)\b", "instruction"),
    (r"\b(i'm working on|i am working on|my project|my server|my plugin|my client|my anticheat|my anti-cheat|my repo|my app|i'm building|i am building|we're building|our server|mein projekt|mein server)\b", "project"),
    (r"\b(i am a|i'm a|i work as|i work at|i live in|i use|i'm using|i am using|my setup|my pc|my computer|i have \d+ ?gb|ich bin|ich benutze|ich nutze)\b", "fact"),
]
_SENT_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")


def extract_candidates(text: str, max_len: int = 400) -> list[tuple[str, str]]:
    """Rule-based extraction of first-person, durable statements. Returns (sentence, kind).
    Only verbatim sentences from the user's own messages are returned; nothing is paraphrased."""
    out, seen = [], set()
    for sent in _SENT_SPLIT.split(text):
        s = normalize(sent)
        if len(s) < 12 or len(s) > max_len or s.startswith(("```", "$", ">")) or s.count("`") > 2:
            continue
        if s.endswith("?"):
            continue  # questions are not facts about the user
        for pat, kind in _PATTERNS:
            if re.search(pat, s, re.I):
                k = content_hash(s)
                if k not in seen:
                    seen.add(k)
                    out.append((s, kind))
                break
    return out


def is_grounded(candidate: str, source: str, threshold: float = 0.7) -> bool:
    """True if most content words of `candidate` occur in `source` (anti-hallucination gate)."""
    words = [w for w in re.findall(r"[a-z0-9äöüß][a-z0-9äöüß+#.\-]{2,}", candidate.lower())
             if w not in {"the", "and", "user", "users", "that", "with", "for", "their", "they", "them", "has", "have", "uses", "prefers", "wants"}]
    if not words:
        return False
    src = source.lower()
    hits = sum(1 for w in words if w in src)
    return hits / len(words) >= threshold
