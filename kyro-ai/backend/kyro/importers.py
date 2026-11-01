"""Importers for data the user explicitly exports and uploads.

Supported inputs (auto-detected):
* ChatGPT data export ZIP or its `conversations.json` (items with a `mapping` tree)
* Claude data export ZIP or its `conversations.json` (items with `chat_messages`), `projects.json`,
  and memory files (`memories.json` / any JSON or text list of memories)
* ChatGPT "Manage memories" text / custom instructions, pasted or uploaded as .txt/.md
* Personal notes (.txt / .md / .pdf / .docx-as-text), and programming projects (ZIP of a repo)

KYRO never connects to ChatGPT or Claude accounts; it only reads files you provide.
"""
from __future__ import annotations

import io
import json
import re
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

from .db import Database, index_history_message, new_id, now, stable_id
from .embeddings import chunk_text
from .memory import MemoryStore, extract_candidates, is_grounded, normalize

MAX_JSON_BYTES = 1_500_000_000


def parse_time(v: Any) -> float | None:
    if v is None or v == "":
        return None
    if isinstance(v, (int, float)):
        return float(v) / (1000 if v > 1e12 else 1)
    try:
        return datetime.fromisoformat(str(v).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


# ---------------------------------------------------------------- parsing (pure functions, unit-tested)
def parse_chatgpt_conversations(data: list[dict]) -> list[dict]:
    """Return [{id,title,created,updated,messages:[{role,content,time}]}] following the active branch."""
    convs = []
    for conv in data:
        mapping = conv.get("mapping") or {}
        node_id = conv.get("current_node")
        if not node_id or node_id not in mapping:  # fall back to the deepest leaf
            leaves = [k for k, v in mapping.items() if not v.get("children")]
            node_id = leaves[-1] if leaves else None
        chain = []
        guard = 0
        while node_id and node_id in mapping and guard < 100_000:
            chain.append(mapping[node_id])
            node_id = mapping[node_id].get("parent")
            guard += 1
        msgs = []
        for node in reversed(chain):
            m = node.get("message")
            if not m:
                continue
            role = (m.get("author") or {}).get("role")
            if role not in ("user", "assistant"):
                continue
            if (m.get("metadata") or {}).get("is_visually_hidden_from_conversation"):
                continue
            c = m.get("content") or {}
            parts = c.get("parts") or ([c.get("text")] if c.get("text") else [])
            text = "\n".join(p if isinstance(p, str) else _chatgpt_part(p) for p in parts if p).strip()
            if c.get("content_type") in ("code", "execution_output") and c.get("text"):
                text = f"```\n{c['text']}\n```"
            if text:
                msgs.append({"id": str(m.get("id") or node.get("id") or len(msgs)),
                             "role": role, "content": text, "time": parse_time(m.get("create_time"))})
        if msgs:
            convs.append({"id": str(conv.get("conversation_id") or conv.get("id") or new_id()),
                          "title": conv.get("title") or "Untitled", "created": parse_time(conv.get("create_time")),
                          "updated": parse_time(conv.get("update_time")), "messages": msgs})
    return convs


def _chatgpt_part(p: Any) -> str:
    if isinstance(p, dict):
        if p.get("content_type") == "image_asset_pointer":
            return "[image]"
        return p.get("text") or ""
    return str(p)


def parse_claude_conversations(data: list[dict] | dict) -> list[dict]:
    if isinstance(data, dict):
        data = [data]
    convs = []
    for conv in data:
        if not isinstance(conv, dict):
            continue
        msgs = []
        for m in conv.get("chat_messages") or conv.get("messages") or []:
            if not isinstance(m, dict):
                continue
            role = {"human": "user", "user": "user", "assistant": "assistant"}.get(m.get("sender") or m.get("role"))
            if not role:
                continue
            text = m.get("text") or ""
            content = m.get("content")
            if not text and isinstance(content, str):
                text = content
            elif not text and isinstance(content, list):
                text = "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
            for a in m.get("attachments") or []:
                if a.get("extracted_content"):
                    text += f"\n\n[Attachment: {a.get('file_name', 'file')}]\n{a['extracted_content'][:20000]}"
            if text.strip():
                msgs.append({"id": str(m.get("uuid") or m.get("id") or len(msgs)),
                             "role": role, "content": text.strip(), "time": parse_time(m.get("created_at"))})
        if msgs:
            convs.append({"id": str(conv.get("uuid") or conv.get("id") or new_id()), "title": conv.get("name") or conv.get("title") or "Untitled",
                          "created": parse_time(conv.get("created_at")), "updated": parse_time(conv.get("updated_at")),
                          "messages": msgs})
    return convs


def parse_claude_projects(data: list[dict]) -> list[dict]:
    out = []
    for p in data:
        out.append({"id": p.get("uuid") or new_id(), "name": (p.get("name") or "Untitled project").strip(),
                    "description": p.get("description") or "", "instructions": p.get("prompt_template") or "",
                    "created": parse_time(p.get("created_at")),
                    "docs": [{"filename": d.get("filename", "doc"), "content": d.get("content", "")} for d in p.get("docs") or []]})
    return out


def parse_memory_list(obj: Any) -> list[dict]:
    """Accept many memory export shapes: list of strings, list of {content|memory|text|value}, {memories:[...]},
    or a plain text block with one memory per line / bullet."""
    items: list[dict] = []
    if isinstance(obj, str):
        for line in obj.splitlines():
            s = re.sub(r"^\s*(?:[-*•]|\d+[.)])\s*", "", line).strip()
            if len(s) >= 4 and not s.startswith("#"):
                items.append({"content": s, "time": None})
        return items
    if isinstance(obj, dict):
        for key in ("memories", "memory", "items", "data", "conversations_memory"):
            if isinstance(obj.get(key), (list, dict)):
                return parse_memory_list(obj[key])
        txt = obj.get("content") or obj.get("memory") or obj.get("text") or obj.get("value")
        if isinstance(txt, str):
            if "\n" in txt.strip():
                return parse_memory_list(txt)
            return [{"content": txt, "time": parse_time(obj.get("created_at") or obj.get("updated_at") or obj.get("timestamp"))}]
        return items
    if isinstance(obj, list):
        for x in obj:
            items += parse_memory_list(x)
    return items


def load_json_or_jsonl(text: str) -> Any:
    """Parse a JSON document, or JSON Lines (one object per line) into a list."""
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        items = []
        for line in text.splitlines():
            if line.strip():
                try:
                    items.append(json.loads(line))
                except json.JSONDecodeError as e:
                    raise ValueError(str(e)) from e
        if not items:
            raise ValueError("empty file")
        return items


def _is_message_list(v: Any) -> bool:
    return isinstance(v, list) and bool(v) and isinstance(v[0], dict) and bool({"sender", "role"} & set(v[0]))


def detect_json_kind(name: str, obj: Any) -> str:
    n = Path(name).name.lower()
    if isinstance(obj, dict) and isinstance(obj.get("data_files"), list) and any("export_url" in f for f in obj["data_files"] if isinstance(f, dict)):
        return "export_manifest"
    if isinstance(obj, dict) and ("chat_messages" in obj or "mapping" in obj or _is_message_list(obj.get("messages"))):
        obj = [obj]  # a single conversation per file
    if isinstance(obj, list) and obj and isinstance(obj[0], dict):
        if "mapping" in obj[0]:
            return "chatgpt_conversations"
        if "chat_messages" in obj[0] or _is_message_list(obj[0].get("messages")):
            return "claude_conversations"
        if "prompt_template" in obj[0] or ("docs" in obj[0] and "uuid" in obj[0]):
            return "claude_projects"
    if "memor" in n:
        return "memories"
    if n == "projects.json":
        return "claude_projects"
    return "unknown"


# ---------------------------------------------------------------- import service
class Importer:
    def __init__(self, db: Database, memory: MemoryStore, router=None, settings=None):
        self.db = db
        self.memory = memory
        self.router = router
        self.settings = settings

    def _start(self, kind: str, filename: str) -> str:
        iid = new_id()
        self.db.execute("INSERT INTO imports(id,kind,filename,status,created_at) VALUES (?,?,?,?,?)", (iid, kind, filename, "running", now()))
        return iid

    def _finish(self, iid: str, stats: dict, error: str = "") -> None:
        self.db.execute("UPDATE imports SET status=?, stats=?, error=? WHERE id=?", ("failed" if error else "done", json.dumps(stats), error, iid))

    async def import_file(self, path: Path, filename: str, *, hint: str = "auto", project_id: str | None = None,
                          use_llm: bool = False, progress: Callable[[dict], None] | None = None) -> dict:
        iid = self._start(hint, filename)
        stats: dict[str, Any] = {"conversations": 0, "messages": 0, "memories_created": 0, "duplicates": 0,
                                 "conflicts": 0, "projects": 0, "notes": 0, "skipped": [], "files": []}
        try:
            lower = filename.lower()
            if hint in ("project_zip", "project") and lower.endswith(".zip"):
                await self._import_project_zip(path, filename, project_id, stats)
            elif lower.endswith(".zip"):
                await self._import_zip(path, stats, project_id, use_llm, progress)
            elif lower.endswith((".json", ".jsonl", ".ndjson")):
                if path.stat().st_size > MAX_JSON_BYTES:
                    raise ValueError("JSON file too large")
                obj = load_json_or_jsonl(path.read_text("utf-8", errors="replace"))
                await self._import_json(filename, obj, stats, project_id, use_llm, progress, hint)
            else:
                text = _read_text_any(path, filename)
                if hint in ("chatgpt_memory", "claude_memory", "memories"):
                    await self._import_memories(parse_memory_list(text), "chatgpt-memory" if hint == "chatgpt_memory" else "claude-memory", filename, stats, project_id)
                elif hint == "custom_instructions":
                    await self._add(text, "instruction", stats, project_id, "custom-instructions", filename, None, verified=True)
                else:
                    await self._import_note(text, filename, stats, project_id)
            self._finish(iid, stats)
        except Exception as e:  # noqa: BLE001
            self._finish(iid, stats, f"{e.__class__.__name__}: {e}")
            raise
        return {"import_id": iid, **stats}

    async def import_text(self, text: str, kind: str, project_id: str | None = None, label: str = "pasted") -> dict:
        iid = self._start(kind, label)
        stats: dict[str, Any] = {"memories_created": 0, "duplicates": 0, "conflicts": 0, "notes": 0, "skipped": []}
        if kind in ("chatgpt_memory", "claude_memory", "memories"):
            src = {"chatgpt_memory": "chatgpt-memory", "claude_memory": "claude-memory"}.get(kind, "manual")
            await self._import_memories(parse_memory_list(text), src, label, stats, project_id)
        elif kind == "custom_instructions":
            await self._add(text, "instruction", stats, project_id, "custom-instructions", label, None, verified=True)
        else:
            await self._import_note(text, label, stats, project_id)
        self._finish(iid, stats)
        return {"import_id": iid, **stats}

    async def _import_zip(self, path: Path, stats: dict, project_id, use_llm, progress) -> None:
        """Import every recognisable data file in an export ZIP, whatever it is called (exports are
        sometimes split into conversations-000.zip, sharded JSON or JSON Lines files)."""
        with zipfile.ZipFile(path) as z:
            infos = [i for i in z.infolist() if not i.is_dir()]
            if sum(i.file_size for i in infos) > 4_000_000_000:
                raise ValueError("ZIP expands to more than 4 GB; refusing (zip-bomb protection)")

            def prio(i: zipfile.ZipInfo) -> int:  # projects before conversations so chats can be linked
                n = Path(i.filename).name.lower()
                return 0 if "project" in n else 2 if "memor" in n else 1

            handled = 0
            data_files = [i for i in infos if i.filename.lower().endswith((".json", ".jsonl", ".ndjson"))]
            for info in sorted(data_files, key=prio):
                name = Path(info.filename).name
                try:
                    obj = load_json_or_jsonl(z.read(info).decode("utf-8", errors="replace"))
                except ValueError:
                    stats["skipped"].append(f"{info.filename}: not valid JSON")
                    continue
                kind = detect_json_kind(name, obj)
                if kind == "unknown":
                    continue  # metadata or UI files in the export; nothing to learn from them
                stats["files"].append(info.filename)
                await self._import_json(name, obj, stats, project_id, use_llm, progress, "auto")
                handled += 1
            for info in infos:
                n = Path(info.filename).name.lower()
                if "memor" in n and n.endswith((".txt", ".md")):
                    stats["files"].append(info.filename)
                    await self._import_memories(parse_memory_list(z.read(info).decode("utf-8", errors="replace")),
                                                "claude-memory", n, stats, project_id)
                    handled += 1
            if not handled:
                notes = [i for i in infos if i.filename.lower().endswith((".md", ".txt"))]
                if not notes:
                    raise ValueError("This ZIP contains no conversations, projects, memories or notes. If your export "
                                     "came as several ZIPs, upload the one named like 'conversations-000.zip'. "
                                     "For a code project, use 'Programming project'.")
                for i in notes[:5000]:
                    await self._import_note(z.read(i).decode("utf-8", errors="replace"), i.filename, stats, project_id)

    async def _import_json(self, name: str, obj: Any, stats: dict, project_id, use_llm, progress, hint: str) -> None:
        if isinstance(obj, dict) and obj.get("version") == 1 and isinstance(obj.get("memories"), list) and "exported_at" in obj:
            r = await self.memory.import_kyro_export(obj)  # KYRO's own export: keeps kind, source, timestamps
            stats["memories_created"] += r["created"]; stats["duplicates"] += r["duplicate"]; stats["conflicts"] += r["conflict"]
            return
        kind = detect_json_kind(name, obj) if hint in ("auto", "chatgpt", "claude") else hint
        if kind == "export_manifest":
            files = ", ".join(f.get("filename", "?") for f in obj["data_files"])
            raise ValueError("This is only the export manifest (a list of download links), not your data. Open each "
                             f"link in the browser where you are logged in, download the ZIPs ({files}) and upload "
                             "the conversations ZIP here.")
        if kind == "chatgpt_conversations":
            await self._import_conversations(parse_chatgpt_conversations(obj), "chatgpt", stats, project_id, use_llm, progress)
        elif kind == "claude_conversations":
            await self._import_conversations(parse_claude_conversations(obj), "claude", stats, project_id, use_llm, progress)
        elif kind == "claude_projects":
            await self._import_claude_projects(parse_claude_projects(obj), stats)
        elif kind in ("memories", "chatgpt_memory", "claude_memory"):
            src = "chatgpt-memory" if kind == "chatgpt_memory" else "claude-memory"
            await self._import_memories(parse_memory_list(obj), src, name, stats, project_id)
        else:
            stats["skipped"].append(f"{name}: unrecognised JSON structure")

    async def _import_conversations(self, convs: list[dict], source: str, stats: dict, project_id, use_llm, progress) -> None:
        for i, conv in enumerate(convs):
            cid = self._store_conversation(conv, source, project_id)
            stats["conversations"] += 1
            stats["messages"] += len(conv["messages"])
            user_text = "\n".join(m["content"] for m in conv["messages"] if m["role"] == "user")
            for m in conv["messages"]:
                if m["role"] != "user":
                    continue
                for sent, kind in extract_candidates(m["content"]):
                    await self._add(sent, kind, stats, project_id, source, cid, m["time"] or conv["created"])
            if use_llm and self.router and user_text.strip():
                await self._llm_extract(user_text, source, cid, conv["created"], stats, project_id)
            if progress and i % 10 == 0:
                progress({"done": i + 1, "total": len(convs)})

    def _store_conversation(self, conv: dict, source: str, project_id) -> str:
        from .archive import index_message

        existing = self.db.one("SELECT id FROM conversations WHERE source=? AND source_id=?", (source, conv["id"]))
        t = now()
        with self.db.transaction() as c:
            if existing:
                cid = existing["id"]
                c.execute("DELETE FROM messages WHERE conversation_id=?", (cid,))
                c.execute("DELETE FROM messages_fts WHERE conversation_id=?", (cid,))
                c.execute("UPDATE conversations SET title=?, updated_at=? WHERE id=?", (conv["title"], conv["updated"] or t, cid))
            else:
                cid = new_id()
                c.execute("INSERT INTO conversations(id,title,project_id,source,source_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                          (cid, conv["title"][:300], project_id, source, conv["id"], conv["created"] or t, conv["updated"] or conv["created"] or t))
            for seq, m in enumerate(conv["messages"]):
                source_mid = str(m.get("id") or seq)
                mid = stable_id(source, conv["id"], source_mid)
                c.execute("INSERT INTO messages(id,conversation_id,seq,role,content,meta,created_at) VALUES (?,?,?,?,?,?,?)",
                          (mid, cid, seq, m["role"], m["content"], json.dumps({"source_message_id": source_mid, "source_time": m["time"]}), m["time"] or conv["created"] or t))
                index_history_message(c, mid, cid, m["content"])
                index_message(c, mid, m["content"])
        return cid

    async def _llm_extract(self, user_text: str, source: str, cid: str, when, stats: dict, project_id) -> None:
        prompt = (
            "Extract durable facts about the USER (identity, preferences, tools, projects, long-term goals, standing "
            "instructions) from the user's messages below. Rules: only information explicitly stated by the user; "
            "quote or minimally rephrase their words; no guesses; skip one-off questions. Return ONLY a JSON array of "
            "short strings, or [] if none.\n\nUSER MESSAGES:\n" + user_text[:12000])
        try:
            raw = await self.router.complete([{"role": "user", "content": prompt}], role="fast")
        except Exception as e:  # noqa: BLE001
            stats["skipped"].append(f"LLM extraction unavailable: {e}")
            return
        m = re.search(r"\[.*\]", raw, re.S)
        try:
            facts = json.loads(m.group(0)) if m else []
        except json.JSONDecodeError:
            facts = []
        for f in facts:
            if isinstance(f, str) and is_grounded(f, user_text):  # anti-hallucination gate
                await self._add(f, "fact", stats, project_id, source, f"{cid}#llm", when)
            elif isinstance(f, str):
                stats.setdefault("rejected_ungrounded", 0)
                stats["rejected_ungrounded"] += 1

    async def _import_claude_projects(self, projects: list[dict], stats: dict) -> None:
        for p in projects:
            pid = ensure_project(self.db, p["name"], p["description"], p["instructions"])
            stats["projects"] += 1
            if p["instructions"].strip():
                await self._add(p["instructions"], "instruction", stats, pid, "claude-project", p["name"], p["created"], verified=True, scope="project")
            for d in p["docs"]:
                for ch in chunk_text(d["content"])[:400]:
                    await self._add(f"[{d['filename']}] {ch}", "document", stats, pid, "claude-project", d["filename"], p["created"], scope="project")

    async def _import_memories(self, items: list[dict], source: str, ref: str, stats: dict, project_id) -> None:
        for it in items:
            kind = "preference" if re.search(r"\b(prefer|likes?|wants?|bevorzugt)\b", it["content"], re.I) else "fact"
            await self._add(it["content"], kind, stats, project_id, source, ref, it.get("time"), verified=True)

    async def _import_note(self, text: str, filename: str, stats: dict, project_id) -> None:
        stats["notes"] = stats.get("notes", 0) + 1
        for ch in chunk_text(text):
            await self._add(ch, "note", stats, project_id, "note", filename, None)

    async def _import_project_zip(self, path: Path, filename: str, project_id: str | None, stats: dict) -> None:
        from .files import safe_extract_zip, scan_project

        name = Path(filename).stem
        pid = project_id or ensure_project(self.db, name, f"Imported from {filename}", "")
        proj = self.db.one("SELECT * FROM projects WHERE id=?", (pid,))
        dest = self.settings.workspace_root / "projects" / re.sub(r"[^\w.\-]+", "_", proj["name"])
        n = safe_extract_zip(path, dest)
        self.db.execute("UPDATE projects SET workspace_path=?, updated_at=? WHERE id=?", (str(dest), now(), pid))
        scan = scan_project(dest)
        stats["files"] = n
        stats["projects"] += 1
        for fact in scan["facts"]:
            await self._add(fact, "project", stats, pid, "project-scan", filename, now(), verified=True, scope="project")
        stats["project_id"] = pid
        stats["workspace"] = str(dest)

    async def _add(self, text: str, kind: str, stats: dict, project_id, source, ref, when, verified=False, scope=None) -> None:
        text = normalize(text)
        if len(text) < 4:
            return
        sc = scope or ("project" if project_id else "general")
        r = await self.memory.add(text[:4000], kind=kind, scope=sc, project_id=project_id if sc == "project" else None,
                                  source=source, source_ref=str(ref), source_time=when, verified=verified)
        key = {"created": "memories_created", "duplicate": "duplicates", "conflict": "conflicts"}[r.status]
        stats[key] = stats.get(key, 0) + 1
        if r.status == "conflict":
            stats["memories_created"] = stats.get("memories_created", 0) + 1


def ensure_project(db: Database, name: str, description: str = "", instructions: str = "") -> str:
    row = db.one("SELECT * FROM projects WHERE lower(name)=lower(?)", (name,))
    t = now()
    if row:
        if instructions and instructions not in row["instructions"]:
            merged = (row["instructions"] + "\n\n" + instructions).strip()
            db.execute("UPDATE projects SET instructions=?, updated_at=? WHERE id=?", (merged, t, row["id"]))
        if description and not row["description"]:
            db.execute("UPDATE projects SET description=? WHERE id=?", (description, row["id"]))
        return row["id"]
    pid = new_id()
    db.execute("INSERT INTO projects(id,name,description,instructions,created_at,updated_at) VALUES (?,?,?,?,?,?)",
               (pid, name, description, instructions, t, t))
    return pid


def _read_text_any(path: Path, filename: str) -> str:
    if filename.lower().endswith(".pdf"):
        from .files import pdf_text

        return pdf_text(path)
    if filename.lower().endswith(".docx"):
        from .files import docx_text

        return docx_text(path)
    return path.read_bytes().decode("utf-8", errors="replace")


async def seed_projects(db: Database, memory: MemoryStore, seed_file: Path) -> int:
    """Load the initial project context (KYROSMP, KYRO AntiCheat, KYRO Client, KYRO AI) once."""
    if not seed_file.exists() or db.one("SELECT 1 x FROM memories WHERE source='seed' LIMIT 1"):
        return 0
    data = json.loads(seed_file.read_text("utf-8"))
    n = 0
    for p in data["projects"]:
        pid = ensure_project(db, p["name"], p["description"], p.get("instructions", ""))
        for fact in p["facts"]:
            await memory.add(fact, kind="project", scope="project", project_id=pid, source="seed",
                             source_ref="initial project context", source_time=now(), verified=True)
            n += 1
    return n
