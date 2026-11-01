"""KYRO AI HTTP API (FastAPI). Run: python -m kyro  (or uvicorn kyro.main:app)."""
from __future__ import annotations

import asyncio
import hmac
import json
import mimetypes
import shutil
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import Body, FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, Response, StreamingResponse
from pydantic import BaseModel

from . import __version__
from .agent.loop import AgentEngine, zip_dir
from .agent.sandbox import SandboxError, Workspace, classify_command
from .chat import ChatService, sse
from .config import Settings
from .db import Database, new_id, now
from .embeddings import Embedder
from .files import FileService
from .importers import Importer, seed_projects
from .llm import LLMError, ModelRouter
from .memory import MemoryStore

ROOT = Path(__file__).resolve().parents[2]  # kyro-ai/
SEED_FILE = ROOT / "seed" / "projects.json"
FRONTEND_DIST = ROOT / "frontend" / "dist"


class Services:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.db = Database(settings.db_path)
        self.router = ModelRouter(settings)
        self.embedder = Embedder(self.router)
        self.memory = MemoryStore(self.db, self.embedder, settings)
        self.files = FileService(self.db, settings)
        self.chat = ChatService(self.db, self.router, self.memory, self.files, self.embedder, settings)
        self.importer = Importer(self.db, self.memory, self.router, settings)
        self.agent = AgentEngine(self.db, self.router, settings, self.memory)


# ------------------------------------------------------------------ request models
class ProjectIn(BaseModel):
    name: str
    description: str = ""
    instructions: str = ""
    workspace_path: str = ""


class ConversationIn(BaseModel):
    title: str = "New chat"
    project_id: str | None = None
    mode: str = "chat"
    model: str = "auto"


class ChatIn(BaseModel):
    text: str = ""
    attachments: list[str] = []
    mode: str | None = None
    model: str | None = None
    web_search: bool = False
    think: bool | None = None
    regenerate: bool = False


class MemoryIn(BaseModel):
    content: str
    kind: str = "fact"
    scope: str = "general"
    project_id: str | None = None
    source: str = "manual"
    verified: bool = True


class AgentIn(BaseModel):
    task: str
    project_id: str | None = None
    workspace: str | None = None
    model: str = "auto"
    conversation_id: str | None = None


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()
    S = Services(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        try:
            n = await seed_projects(S.db, S.memory, SEED_FILE)
            if n:
                print(f"[kyro] seeded {n} project memories")
        except Exception as e:  # noqa: BLE001
            print(f"[kyro] WARNING: seeding failed: {e}")
        yield

    app = FastAPI(title="KYRO AI", version=__version__, lifespan=lifespan)
    app.state.services = S
    app.add_middleware(CORSMiddleware, allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
                       allow_methods=["*"], allow_headers=["*"])

    @app.middleware("http")
    async def auth(request: Request, call_next):
        a = settings["auth"]
        if a.get("enabled") and request.url.path.startswith("/api/") and request.url.path != "/api/health":
            tok = request.headers.get("x-kyro-token") or request.query_params.get("token") or ""
            if not hmac.compare_digest(tok, a.get("token", "")):
                return JSONResponse({"detail": "Unauthorized: missing or wrong KYRO access token"}, status_code=401)
        return await call_next(request)

    @app.exception_handler(LLMError)
    async def llm_err(_, e: LLMError):
        return JSONResponse({"detail": str(e)}, status_code=503)

    @app.exception_handler(SandboxError)
    async def sb_err(_, e: SandboxError):
        return JSONResponse({"detail": str(e)}, status_code=403)

    # ---------------------------------------------------------------- system
    @app.get("/api/health")
    async def health():
        o = await S.router.ollama.health()
        return {"ok": True, "version": __version__, "ollama": o, "embedding_model": await S.embedder.active_model() if o["ok"] else "hash-lex-512",
                "auth_required": bool(settings["auth"]["enabled"]), "data_dir": str(settings.data_dir)}

    @app.get("/api/models")
    async def models():
        return await S.router.available_models()

    @app.post("/api/models/pull")
    async def pull(body: dict = Body(...)):
        name = body["model"]

        async def gen():
            try:
                async for ev in S.router.ollama.pull(name):
                    yield sse("progress", ev)
                S.embedder.reset()
                yield sse("done", {"model": name})
            except Exception as e:  # noqa: BLE001
                yield sse("error", {"message": str(e)})
        return StreamingResponse(gen(), media_type="text/event-stream")

    @app.get("/api/settings")
    async def get_settings():
        return settings.public()

    @app.patch("/api/settings")
    async def patch_settings(patch: dict = Body(...)):
        patch.pop("data_dir", None); patch.pop("workspace_root_resolved", None)
        if patch.get("cloud", {}).get("api_key") == "********":
            patch["cloud"].pop("api_key")
        if patch.get("auth", {}).get("token") == "********":
            patch["auth"].pop("token")
        old_embed = settings["models"].get("embedding")
        out = settings.update(patch)
        if settings["models"].get("embedding") != old_embed:
            S.embedder.reset()
        S.db.audit("user", "settings.update", list(patch.keys()))
        return out

    @app.get("/api/settings/token")
    async def reveal_token(request: Request):
        # only reachable from the local machine; shows the token so the user can log in from another device
        if request.client and request.client.host not in ("127.0.0.1", "::1", "localhost", "testclient"):
            raise HTTPException(403, "Token can only be revealed locally")
        return {"token": settings["auth"].get("token", "")}

    # ---------------------------------------------------------------- projects
    @app.get("/api/projects")
    async def list_projects():
        return S.db.all("SELECT p.*, (SELECT COUNT(*) FROM memories m WHERE m.project_id=p.id) memory_count, "
                        "(SELECT COUNT(*) FROM conversations c WHERE c.project_id=p.id) conversation_count FROM projects p ORDER BY name")

    @app.post("/api/projects")
    async def create_project(p: ProjectIn):
        if S.db.one("SELECT 1 x FROM projects WHERE lower(name)=lower(?)", (p.name,)):
            raise HTTPException(409, "A project with this name already exists")
        pid, t = new_id(), now()
        S.db.execute("INSERT INTO projects(id,name,description,instructions,workspace_path,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                     (pid, p.name.strip(), p.description, p.instructions, p.workspace_path.strip(), t, t))
        return S.db.one("SELECT * FROM projects WHERE id=?", (pid,))

    @app.patch("/api/projects/{pid}")
    async def update_project(pid: str, patch: dict = Body(...)):
        allowed = {k: v for k, v in patch.items() if k in ("name", "description", "instructions", "workspace_path")}
        if "workspace_path" in allowed and allowed["workspace_path"]:
            wp = Path(allowed["workspace_path"]).expanduser()
            if not wp.is_dir():
                raise HTTPException(400, f"Folder does not exist: {wp}")
            allowed["workspace_path"] = str(wp.resolve())
        if allowed:
            sets = ", ".join(f"{k}=?" for k in allowed)
            S.db.execute(f"UPDATE projects SET {sets}, updated_at=? WHERE id=?", (*allowed.values(), now(), pid))
        return S.db.one("SELECT * FROM projects WHERE id=?", (pid,))

    @app.delete("/api/projects/{pid}")
    async def delete_project(pid: str):
        S.db.execute("DELETE FROM projects WHERE id=?", (pid,))
        S.db.audit("user", "project.delete", pid)
        return {"ok": True}

    # ---------------------------------------------------------------- conversations
    @app.get("/api/conversations")
    async def list_conversations(project_id: str | None = None, source: str | None = None, limit: int = 300, offset: int = 0):
        where, args = ["1=1"], []
        if project_id:
            where.append("project_id=?"); args.append(project_id)
        if source:
            where.append("source=?"); args.append(source)
        return S.db.all(f"SELECT id,title,project_id,mode,model,source,pinned,created_at,updated_at FROM conversations "
                        f"WHERE {' AND '.join(where)} ORDER BY pinned DESC, updated_at DESC LIMIT ? OFFSET ?", (*args, limit, offset))

    @app.post("/api/conversations")
    async def create_conversation(c: ConversationIn):
        return S.chat.create_conversation(c.title, c.project_id, c.mode, c.model)

    @app.get("/api/conversations/{cid}")
    async def get_conversation(cid: str):
        conv = S.db.one("SELECT * FROM conversations WHERE id=?", (cid,))
        if not conv:
            raise HTTPException(404, "Conversation not found")
        msgs = S.chat.messages(cid)
        att_ids = {a for m in msgs for a in m["attachments"]}
        atts = {a: S.files.get(a) for a in att_ids}
        return {**conv, "messages": msgs, "attachments": {k: v for k, v in atts.items() if v}}

    @app.patch("/api/conversations/{cid}")
    async def update_conversation(cid: str, patch: dict = Body(...)):
        allowed = {k: v for k, v in patch.items() if k in ("title", "project_id", "mode", "model", "pinned", "personality")}
        if allowed:
            sets = ", ".join(f"{k}=?" for k in allowed)
            S.db.execute(f"UPDATE conversations SET {sets} WHERE id=?", (*allowed.values(), cid))
        return S.db.one("SELECT * FROM conversations WHERE id=?", (cid,))

    @app.delete("/api/conversations/{cid}")
    async def delete_conversation(cid: str):
        S.db.execute("DELETE FROM messages_fts WHERE conversation_id=?", (cid,))
        S.db.execute("DELETE FROM conversations WHERE id=?", (cid,))
        return {"ok": True}

    @app.get("/api/conversations/{cid}/export")
    async def export_conversation(cid: str, fmt: str = "md"):
        conv = S.db.one("SELECT title FROM conversations WHERE id=?", (cid,))
        if not conv:
            raise HTTPException(404)
        body = S.chat.export(cid, fmt)
        name = "".join(ch if ch.isalnum() else "_" for ch in conv["title"])[:60] or "conversation"
        return Response(body, media_type="application/json" if fmt == "json" else "text/markdown",
                        headers={"Content-Disposition": f'attachment; filename="{name}.{"json" if fmt == "json" else "md"}"'})

    @app.post("/api/conversations/{cid}/chat")
    async def chat(cid: str, body: ChatIn):
        return StreamingResponse(S.chat.stream_turn(cid, body.text, attachment_ids=body.attachments, mode=body.mode, model=body.model,
                                                    web_search=body.web_search, think=body.think, regenerate=body.regenerate),
                                 media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.get("/api/search")
    async def search(q: str):
        return {"messages": S.chat.search_history(q),
                "conversations": S.db.all("SELECT id,title,source,updated_at FROM conversations WHERE title LIKE ? ORDER BY updated_at DESC LIMIT 30", (f"%{q}%",))}

    # ---------------------------------------------------------------- files
    @app.post("/api/files")
    async def upload(files: list[UploadFile] = File(...)):
        out = []
        for f in files:
            data = await f.read()
            try:
                out.append(await asyncio.to_thread(S.files.store, f.filename or "file", data, f.content_type))
            except ValueError as e:
                out.append({"filename": f.filename, "error": str(e)})
        return out

    @app.get("/api/files/{aid}")
    async def file_info(aid: str):
        r = S.files.get(aid)
        if not r:
            raise HTTPException(404)
        return r

    @app.get("/api/files/{aid}/raw")
    async def file_raw(aid: str):
        r, p = S.files.full(aid), S.files.path(aid)
        if not r or not p or not p.exists():
            raise HTTPException(404)
        return FileResponse(p, media_type=r["mime"], filename=r["filename"])

    # ---------------------------------------------------------------- memory
    @app.get("/api/memories")
    async def list_memories(scope: str | None = None, project_id: str | None = None, status: str | None = None,
                            source: str | None = None, kind: str | None = None, q: str | None = None, limit: int = 200, offset: int = 0):
        return S.memory.list(scope=scope, project_id=project_id, status=status, source=source, kind=kind, q=q, limit=limit, offset=offset)

    @app.get("/api/memories/stats")
    async def memory_stats():
        return S.memory.stats()

    @app.get("/api/memories/search")
    async def memory_search(q: str, project_id: str | None = None, top_k: int = 10):
        return await S.memory.search(q, project_id=project_id, top_k=top_k, min_score=0.0)

    @app.post("/api/memories")
    async def create_memory(m: MemoryIn):
        try:
            r = await S.memory.add(m.content, kind=m.kind, scope=m.scope, project_id=m.project_id, source=m.source,
                                   source_time=now(), verified=m.verified)
        except ValueError as e:
            raise HTTPException(400, str(e))
        return {**r.dict(), "memory": S.memory.get(r.id)}

    @app.get("/api/memories/export")
    async def export_memories(fmt: str = "json", scope: str | None = None, project_id: str | None = None):
        body = S.memory.export(fmt, scope=scope, project_id=project_id)
        return Response(body, media_type="text/markdown" if fmt == "md" else "application/json",
                        headers={"Content-Disposition": f'attachment; filename="kyro-memories.{"md" if fmt == "md" else "json"}"'})

    @app.post("/api/memories/reembed")
    async def reembed():
        return await S.memory.reembed_all()

    @app.post("/api/memories/resolve")
    async def resolve(body: dict = Body(...)):
        return S.memory.resolve_conflict(body["keep_id"], body["other_id"], body.get("action", "keep_one"))

    @app.get("/api/memories/{mid}")
    async def get_memory(mid: str):
        m = S.memory.get(mid)
        if not m:
            raise HTTPException(404)
        return m

    @app.patch("/api/memories/{mid}")
    async def update_memory(mid: str, patch: dict = Body(...)):
        try:
            return await S.memory.update(mid, patch)
        except KeyError:
            raise HTTPException(404)
        except ValueError as e:
            raise HTTPException(400, str(e))

    @app.delete("/api/memories/{mid}")
    async def delete_memory(mid: str):
        if not S.memory.delete(mid):
            raise HTTPException(404)
        return {"ok": True}

    @app.post("/api/memories/bulk-delete")
    async def bulk_delete(body: dict = Body(...)):
        n = sum(1 for mid in body.get("ids", []) if S.memory.delete(mid))
        return {"deleted": n}

    # ---------------------------------------------------------------- imports
    @app.post("/api/import")
    async def import_file(file: UploadFile = File(...), kind: str = Form("auto"), project_id: str = Form(""), use_llm: bool = Form(False)):
        tmpdir = Path(tempfile.mkdtemp(prefix="kyro-import-", dir=settings.data_dir))
        try:
            path = tmpdir / "upload.bin"
            with open(path, "wb") as out:
                shutil.copyfileobj(file.file, out, 1024 * 1024)
            return await S.importer.import_file(path, file.filename or "upload", hint=kind, project_id=project_id or None, use_llm=use_llm)
        except (ValueError, json.JSONDecodeError) as e:
            raise HTTPException(400, f"Import failed: {e}")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)

    @app.post("/api/import/text")
    async def import_text(body: dict = Body(...)):
        if not (body.get("text") or "").strip():
            raise HTTPException(400, "Text is empty")
        return await S.importer.import_text(body["text"], body.get("kind", "note"), body.get("project_id") or None, body.get("label", "pasted"))

    @app.get("/api/imports")
    async def list_imports():
        rows = S.db.all("SELECT * FROM imports ORDER BY created_at DESC LIMIT 100")
        for r in rows:
            r["stats"] = json.loads(r["stats"])
        return rows

    # ---------------------------------------------------------------- agent
    @app.post("/api/agent/runs")
    async def start_agent(body: AgentIn):
        run = S.agent.start(body.task, workspace=body.workspace, project_id=body.project_id, model=body.model,
                            conversation_id=body.conversation_id)
        return S.agent.snapshot(run)

    @app.get("/api/agent/runs")
    async def list_runs():
        live = {r.id: S.agent.snapshot(r) for r in S.agent.runs.values()}
        hist = S.db.all("SELECT id, task, status, phase, workspace, project_id, created_at, updated_at, verification FROM agent_runs ORDER BY created_at DESC LIMIT 100")
        for h in hist:
            h["verification"] = json.loads(h["verification"] or "{}")
            h["live"] = h["id"] in live
            if h["live"]:
                h.update(status=live[h["id"]]["status"], phase=live[h["id"]]["phase"])
        return hist

    def _run(run_id: str):
        run = S.agent.runs.get(run_id)
        if not run:
            raise HTTPException(404, "Run not found (runs are kept in memory until the server restarts)")
        return run

    @app.get("/api/agent/runs/{run_id}")
    async def get_run(run_id: str):
        run = _run(run_id)
        return {**S.agent.snapshot(run), "events": run.events}

    @app.get("/api/agent/runs/{run_id}/events")
    async def run_events(run_id: str, request: Request):
        run = _run(run_id)
        q: asyncio.Queue = asyncio.Queue()
        backlog = list(run.events)
        finished = run.status in ("done", "failed", "cancelled") and (not run.task_handle or run.task_handle.done())
        if not finished:
            run.subscribers.append(q)

        async def gen():
            try:
                for ev in backlog:
                    yield sse(ev["type"], ev)
                if finished:
                    yield sse("end", {}); return
                while True:
                    if await request.is_disconnected():
                        break
                    try:
                        ev = await asyncio.wait_for(q.get(), timeout=15)
                    except asyncio.TimeoutError:
                        yield ": keep-alive\n\n"; continue
                    if ev is None:
                        yield sse("end", {}); break
                    yield sse(ev["type"], ev)
            finally:
                if q in run.subscribers:
                    run.subscribers.remove(q)
        return StreamingResponse(gen(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})

    @app.post("/api/agent/runs/{run_id}/approve")
    async def approve(run_id: str, body: dict = Body(...)):
        try:
            S.agent.approve(run_id, bool(body.get("approved")))
        except ValueError as e:
            raise HTTPException(409, str(e))
        return {"ok": True}

    @app.post("/api/agent/runs/{run_id}/cancel")
    async def cancel(run_id: str):
        _run(run_id)
        S.agent.cancel(run_id)
        return {"ok": True}

    @app.get("/api/agent/runs/{run_id}/diff")
    async def run_diff(run_id: str):
        _run(run_id)
        return S.agent.diffs(run_id)

    # ---------------------------------------------------------------- workspace (explorer / Monaco / terminal)
    def _ws(project_id: str | None, sub: str | None = None) -> Workspace:
        if project_id:
            p = S.db.one("SELECT * FROM projects WHERE id=?", (project_id,))
            if not p:
                raise HTTPException(404, "Project not found")
            root = Path(p["workspace_path"]) if p["workspace_path"] else settings.workspace_root / "projects" / "".join(
                ch if ch.isalnum() or ch in "._-" else "_" for ch in p["name"])
            return Workspace(root)
        ws = Workspace(settings.workspace_root)
        return Workspace(ws.resolve(sub)) if sub else ws

    @app.get("/api/workspace/tree")
    async def ws_tree(project_id: str | None = None, root: str | None = None, max_entries: int = 5000):
        ws = _ws(project_id, root)
        skip = {".git", "node_modules", "__pycache__", ".gradle", ".venv", "venv"}
        entries = []
        for p in sorted(ws.root.rglob("*"), key=lambda x: str(x).lower()):
            rel = p.relative_to(ws.root)
            if any(x in skip for x in rel.parts):
                continue
            entries.append({"path": str(rel).replace("\\", "/"), "dir": p.is_dir(), "size": p.stat().st_size if p.is_file() else 0})
            if len(entries) >= max_entries:
                break
        return {"root": str(ws.root), "entries": entries}

    @app.get("/api/workspace/file")
    async def ws_read(path: str, project_id: str | None = None, root: str | None = None):
        ws = _ws(project_id, root)
        p = ws.resolve(path)
        if not p.is_file():
            raise HTTPException(404, "File not found")
        if p.stat().st_size > 5_000_000:
            raise HTTPException(413, "File too large for the editor (>5 MB)")
        raw = p.read_bytes()
        if b"\x00" in raw[:8000]:
            return {"path": path, "binary": True, "size": len(raw)}
        return {"path": path, "binary": False, "content": raw.decode("utf-8", errors="replace")}

    @app.put("/api/workspace/file")
    async def ws_write(body: dict = Body(...)):
        ws = _ws(body.get("project_id"), body.get("root"))
        p = ws.resolve(body["path"])
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body.get("content", ""), "utf-8")
        return {"ok": True, "path": ws.rel(p)}

    @app.delete("/api/workspace/file")
    async def ws_delete(path: str, project_id: str | None = None, root: str | None = None):
        ws = _ws(project_id, root)
        p = ws.resolve(path)
        if p == ws.root:
            raise HTTPException(400, "Refusing to delete the workspace root")
        if p.is_dir():
            shutil.rmtree(p)
        elif p.exists():
            p.unlink()
        S.db.audit("user", "workspace.delete", str(p))
        return {"ok": True}

    @app.post("/api/workspace/terminal")
    async def ws_terminal(body: dict = Body(...)):
        """Commands typed by the user in the terminal panel. Typing is the approval, but catastrophic patterns stay blocked."""
        ws = _ws(body.get("project_id"), body.get("root"))
        cmd = (body.get("command") or "").strip()
        if not cmd:
            raise HTTPException(400, "Empty command")
        d = classify_command(cmd, [])
        if d.level == "blocked":
            raise HTTPException(403, f"Blocked by safety policy: {d.reason}")
        S.db.audit("user", "terminal.run", cmd)
        return await asyncio.to_thread(ws.run, cmd, int(body.get("timeout") or settings["agent"]["command_timeout"]))

    @app.get("/api/workspace/download")
    async def ws_download(project_id: str | None = None, root: str | None = None, run_id: str | None = None):
        ws = S.agent.runs[run_id].workspace if run_id and run_id in S.agent.runs else _ws(project_id, root)
        data = await asyncio.to_thread(zip_dir, ws.root)
        return Response(data, media_type="application/zip", headers={"Content-Disposition": f'attachment; filename="{ws.root.name}.zip"'})

    # ---------------------------------------------------------------- web & images
    @app.post("/api/web/search")
    async def web_search(body: dict = Body(...)):
        from .websearch import research
        return await research(body["query"], searxng_url=settings["searxng_url"], fetch=int(body.get("fetch", 0)))

    @app.get("/api/images/status")
    async def image_status():
        url = settings["comfyui_url"]
        if not url:
            return {"available": False, "reason": "ComfyUI URL not configured (Settings → Image generation)"}
        from .imagegen import ComfyUI
        try:
            return {"available": True, "checkpoints": await ComfyUI(url).checkpoints()}
        except Exception as e:  # noqa: BLE001
            return {"available": False, "reason": f"ComfyUI not reachable: {e}"}

    @app.post("/api/images/generate")
    async def image_generate(body: dict = Body(...)):
        url = settings["comfyui_url"]
        if not url:
            raise HTTPException(400, "Image generation needs ComfyUI. Set its URL in Settings.")
        from .imagegen import ComfyUI
        init = S.files.path(body["init_attachment_id"]) if body.get("init_attachment_id") else None
        paths = await ComfyUI(url).generate(body["prompt"], settings.generated_dir, negative=body.get("negative", "blurry, low quality"),
                                            ckpt=body.get("checkpoint"), width=int(body.get("width", 1024)), height=int(body.get("height", 1024)),
                                            steps=int(body.get("steps", 25)), init_image_path=init, denoise=float(body.get("denoise", 0.6)))
        out = []
        for p in paths:
            out.append(await asyncio.to_thread(S.files.store, p.name.split("_", 1)[-1], p.read_bytes(), "image/png"))
        return out

    # ---------------------------------------------------------------- frontend
    if FRONTEND_DIST.exists():
        @app.get("/{full_path:path}", include_in_schema=False)
        async def spa(full_path: str):
            if full_path.startswith("api/"):
                raise HTTPException(404)
            target = (FRONTEND_DIST / full_path).resolve()
            if full_path and FRONTEND_DIST in target.parents and target.is_file():
                return FileResponse(target, media_type=mimetypes.guess_type(str(target))[0])
            return FileResponse(FRONTEND_DIST / "index.html")
    else:
        @app.get("/", include_in_schema=False)
        async def no_frontend():
            return PlainTextResponse("KYRO AI backend is running. Frontend not built: run 'npm run build' in kyro-ai/frontend "
                                     "or use the dev server at http://localhost:5173")

    return app


app = None  # created lazily by __main__ / uvicorn factory


def factory() -> FastAPI:
    return create_app()
