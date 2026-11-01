"""Autonomous coding agent: PLAN → READ → IMPLEMENT → TEST → DEBUG → VERIFY → DELIVER.

The agent uses native tool calling (Ollama `tools` / OpenAI `tools`). For models without native tool
support it also accepts a fenced ```tool {"name": ..., "arguments": {...}} block.

Verification is evidence-based: the ledger records every build/test command KYRO actually executed with its
exit code. The final report says "verified" only if the last build/test runs after the last edit passed; the
model's own claims never count as verification.
"""
from __future__ import annotations

import asyncio
import difflib
import fnmatch
import io
import json
import re
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..db import Database, new_id, now
from ..llm import LLMError, ModelRouter
from .sandbox import SandboxError, Workspace, classify_command, command_kind

PHASES = ["PLAN", "READ", "IMPLEMENT", "TEST", "DEBUG", "VERIFY", "DELIVER"]

SYSTEM = """You are KYRO Agent, an autonomous senior software engineer working in a sandboxed workspace.
Workspace root: {root}
Operating system of the user's machine: {os}

Follow this loop and announce phases with set_phase:
1. PLAN: call update_plan with concrete steps.
2. READ: inspect relevant files (list_files, read_file, search_code) before changing them. Never guess file contents.
3. IMPLEMENT: create/modify files with write_file (whole file) or edit_file (exact unique replacement).
4. TEST: build and run tests with run_command (e.g. mvn -q package, gradlew build, npm test, pytest).
5. DEBUG: if something fails, read the error output, fix the cause, and re-run.
6. VERIFY: re-run the build/tests after your last edit.
7. DELIVER: call finish with a summary of what changed, how it was verified, and what remains.

Rules:
- Only report a build/test as passing if a run_command result in this session shows exit_code 0.
- If you could not run tests (missing toolchain, approval denied), say "not verified" in finish.
- Keep edits minimal and focused; preserve the user's code style. Use relative paths.
- Destructive operations (delete, git push, rm, etc.) require user approval; explain why you need them.
- Minecraft work: target Paper/Folia {mc_hint} with Java 21; on Folia use region/entity schedulers, never the global Bukkit scheduler for entity/world access.
{project}
"""

TOOLS: list[dict] = [
    {"name": "set_phase", "description": "Announce the current phase.", "parameters": {"type": "object", "properties": {
        "phase": {"type": "string", "enum": PHASES}, "note": {"type": "string"}}, "required": ["phase"]}},
    {"name": "update_plan", "description": "Create or update the step-by-step plan.", "parameters": {"type": "object", "properties": {
        "steps": {"type": "array", "items": {"type": "string"}}}, "required": ["steps"]}},
    {"name": "list_files", "description": "List files under a directory (relative path).", "parameters": {"type": "object", "properties": {
        "path": {"type": "string"}, "depth": {"type": "integer"}}}},
    {"name": "read_file", "description": "Read a text file. Optional 1-based line range.", "parameters": {"type": "object", "properties": {
        "path": {"type": "string"}, "start_line": {"type": "integer"}, "end_line": {"type": "integer"}}, "required": ["path"]}},
    {"name": "write_file", "description": "Create or overwrite a file with the full content.", "parameters": {"type": "object", "properties": {
        "path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
    {"name": "edit_file", "description": "Replace an exact, unique snippet in a file.", "parameters": {"type": "object", "properties": {
        "path": {"type": "string"}, "old": {"type": "string"}, "new": {"type": "string"}}, "required": ["path", "old", "new"]}},
    {"name": "delete_file", "description": "Delete a file (requires user approval).", "parameters": {"type": "object", "properties": {
        "path": {"type": "string"}, "reason": {"type": "string"}}, "required": ["path", "reason"]}},
    {"name": "search_code", "description": "Regex search across files. Optional glob filter like *.java.", "parameters": {"type": "object", "properties": {
        "pattern": {"type": "string"}, "glob": {"type": "string"}}, "required": ["pattern"]}},
    {"name": "run_command", "description": "Run a shell command in the workspace (build, test, git...). May require approval.", "parameters": {"type": "object", "properties": {
        "command": {"type": "string"}, "timeout": {"type": "integer"}}, "required": ["command"]}},
    {"name": "inspect_archive", "description": "List contents/metadata of a .zip or .jar in the workspace.", "parameters": {"type": "object", "properties": {
        "path": {"type": "string"}}, "required": ["path"]}},
    {"name": "finish", "description": "Deliver: final summary for the user.", "parameters": {"type": "object", "properties": {
        "summary": {"type": "string"}, "next_steps": {"type": "string"}}, "required": ["summary"]}},
]
OLLAMA_TOOLS = [{"type": "function", "function": t} for t in TOOLS]
SKIP_DIRS = {".git", "node_modules", "target", "build", ".gradle", "__pycache__", "dist", ".idea", ".venv", "venv", "out"}


@dataclass
class AgentRun:
    id: str
    task: str
    workspace: Workspace
    model: str
    project_id: str | None = None
    conversation_id: str | None = None
    status: str = "running"
    phase: str = "PLAN"
    plan: list[str] = field(default_factory=list)
    events: list[dict] = field(default_factory=list)
    ledger: list[dict] = field(default_factory=list)          # executed build/test commands
    originals: dict[str, str | None] = field(default_factory=dict)  # file -> content before first change
    last_edit_at: float = 0.0
    pending: dict | None = None
    approval_event: asyncio.Event = field(default_factory=asyncio.Event)
    approval_result: bool = False
    cancelled: bool = False
    subscribers: list[asyncio.Queue] = field(default_factory=list)
    summary: str = ""
    task_handle: Any = None

    def verification(self) -> dict:
        runs = [r for r in self.ledger if r["at"] >= self.last_edit_at] if self.last_edit_at else self.ledger
        if not self.ledger:
            return {"status": "not_verified", "detail": "No build or test command was executed.", "runs": []}
        if not runs:
            return {"status": "stale", "detail": "Files were changed after the last build/test run.", "runs": self.ledger[-5:]}
        if all(r["exit_code"] == 0 for r in runs):
            return {"status": "verified", "detail": f"{len(runs)} build/test command(s) after the last edit exited with code 0.", "runs": runs[-5:]}
        return {"status": "failing", "detail": "The latest build/test run after the last edit failed.", "runs": runs[-5:]}


class AgentEngine:
    def __init__(self, db: Database, router: ModelRouter, settings, memory=None):
        self.db, self.router, self.settings, self.memory = db, router, settings, memory
        self.runs: dict[str, AgentRun] = {}

    # ---------------------------------------------------------------- lifecycle
    def start(self, task: str, *, workspace: str | None = None, project_id: str | None = None, model: str = "auto",
              conversation_id: str | None = None) -> AgentRun:
        root = self._workspace_for(workspace, project_id)
        _, model_name, _ = self.router.resolve(model, "agent", task)
        run = AgentRun(id=new_id(), task=task, workspace=Workspace(root), model=model if model.startswith("cloud:") else model_name,
                       project_id=project_id, conversation_id=conversation_id)
        self.runs[run.id] = run
        t = now()
        self.db.execute("INSERT INTO agent_runs(id,conversation_id,project_id,task,status,phase,workspace,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        (run.id, conversation_id, project_id, task, "running", "PLAN", str(root), t, t))
        run.task_handle = asyncio.ensure_future(self._run(run))
        return run

    def _workspace_for(self, workspace: str | None, project_id: str | None) -> Path:
        base = self.settings.workspace_root
        if project_id:
            p = self.db.one("SELECT * FROM projects WHERE id=?", (project_id,))
            if p and p["workspace_path"]:
                return Path(p["workspace_path"])  # user-configured project folder (explicitly authorised)
            if p:
                return base / "projects" / re.sub(r"[^\w.\-]+", "_", p["name"])
        sub = re.sub(r"[^\w.\-/]+", "_", workspace or f"runs/{time.strftime('%Y%m%d-%H%M%S')}").strip("/")
        target = (base / sub).resolve()
        if base not in target.parents and target != base:
            raise SandboxError("workspace must be inside the KYRO workspace root")
        return target

    def cancel(self, run_id: str) -> None:
        run = self.runs[run_id]
        run.cancelled = True
        run.approval_result = False
        run.approval_event.set()
        if run.task_handle:
            run.task_handle.cancel()

    def approve(self, run_id: str, approved: bool) -> None:
        run = self.runs[run_id]
        if not run.pending:
            raise ValueError("No pending approval")
        run.approval_result = approved
        self.db.audit("user", "agent.approval", {"run": run_id, "approved": approved, "request": run.pending})
        run.approval_event.set()

    def emit(self, run: AgentRun, type_: str, data: dict) -> None:
        ev = {"type": type_, "ts": now(), **data}
        run.events.append(ev)
        for q in list(run.subscribers):
            q.put_nowait(ev)
        if type_ in ("phase", "status", "finish", "error", "verification"):
            self.db.execute("UPDATE agent_runs SET status=?, phase=?, events=?, verification=?, updated_at=? WHERE id=?",
                            (run.status, run.phase, json.dumps(run.events[-400:], default=str)[:5_000_000],
                             json.dumps(run.verification(), default=str), now(), run.id))

    def set_phase(self, run: AgentRun, phase: str, note: str = "") -> None:
        if phase in PHASES and phase != run.phase:
            run.phase = phase
            self.emit(run, "phase", {"phase": phase, "note": note})

    # ---------------------------------------------------------------- main loop
    async def _run(self, run: AgentRun) -> None:
        import platform

        project_txt = ""
        if run.project_id:
            p = self.db.one("SELECT * FROM projects WHERE id=?", (run.project_id,))
            if p:
                project_txt = f"Project: {p['name']} — {p['description']}\nProject instructions:\n{p['instructions']}"
                if self.memory:
                    try:
                        mems = await self.memory.search(run.task, project_id=run.project_id, top_k=10)
                        if mems:
                            project_txt += "\nRelevant project memories:\n" + "\n".join(f"- {m['content']}" for m in mems)
                    except Exception:  # noqa: BLE001
                        pass
        system = SYSTEM.format(root=run.workspace.root, os=platform.system(), mc_hint="1.21.x", project=project_txt)
        msgs: list[dict] = [{"role": "system", "content": system}, {"role": "user", "content": run.task}]
        if run.model.startswith("cloud:"):
            provider, model = self.router.cloud, run.model[6:]
        else:
            provider, model = self.router.ollama, run.model
        self.emit(run, "status", {"status": "running", "model": model, "workspace": str(run.workspace.root)})
        self.emit(run, "phase", {"phase": "PLAN", "note": "Starting"})
        max_steps = int(self.settings["agent"]["max_steps"])
        nudged = 0
        try:
            for step in range(max_steps):
                if run.cancelled:
                    break
                text, calls = "", []
                async for ch in provider.chat(model, msgs, tools=OLLAMA_TOOLS, options={"temperature": 0.2}, think=False):
                    if ch.text:
                        text += ch.text
                        self.emit(run, "token", {"delta": ch.text})
                    calls += ch.tool_calls
                if not calls:
                    calls = parse_fallback_tool_calls(text)
                msgs.append({"role": "assistant", "content": text, **({"tool_calls": calls} if calls else {})})
                if text.strip():
                    self.emit(run, "message", {"content": text})
                if not calls:
                    if nudged < 2 and step < max_steps - 1:
                        nudged += 1
                        msgs.append({"role": "user", "content": "Continue using the tools. When completely done, call finish."})
                        continue
                    run.summary = text
                    break
                finished = False
                for call in calls:
                    name, args = call["function"]["name"], call["function"].get("arguments") or {}
                    self.emit(run, "tool_call", {"name": name, "arguments": _preview_args(args)})
                    try:
                        result = await self._exec(run, name, args)
                    except SandboxError as e:
                        result = {"error": f"sandbox: {e}"}
                    except Exception as e:  # noqa: BLE001
                        result = {"error": f"{e.__class__.__name__}: {e}"}
                    self.emit(run, "tool_result", {"name": name, "result": _preview_result(result)})
                    msgs.append({"role": "tool", "tool_name": name, "tool_call_id": call.get("id", ""),
                                 "content": json.dumps(result, default=str)[:24000]})
                    if name == "finish":
                        finished = True
                if finished:
                    break
            else:
                run.summary = run.summary or f"Stopped after the maximum of {max_steps} steps."
            run.status = "cancelled" if run.cancelled else "done"
        except asyncio.CancelledError:
            run.status = "cancelled"
        except LLMError as e:
            run.status = "failed"
            self.emit(run, "error", {"message": str(e)})
        except Exception as e:  # noqa: BLE001
            run.status = "failed"
            self.emit(run, "error", {"message": f"{e.__class__.__name__}: {e}"})
        run.phase = "DELIVER"
        ver = run.verification()
        self.emit(run, "verification", ver)
        self.emit(run, "finish", {"status": run.status, "summary": run.summary, "verification": ver,
                                  "changed_files": list(run.originals.keys())})
        for q in list(run.subscribers):
            q.put_nowait(None)

    # ---------------------------------------------------------------- tools
    async def _approval(self, run: AgentRun, kind: str, detail: dict) -> bool:
        run.pending = {"id": new_id(), "kind": kind, **detail}
        run.approval_event = asyncio.Event()
        prev = run.status
        run.status = "waiting_approval"
        self.emit(run, "approval_request", run.pending)
        await run.approval_event.wait()
        ok = run.approval_result and not run.cancelled
        run.pending = None
        run.status = prev if not run.cancelled else "cancelled"
        self.emit(run, "approval_result", {"approved": ok})
        return ok

    def _remember_original(self, run: AgentRun, p: Path) -> None:
        rel = run.workspace.rel(p)
        if rel not in run.originals:
            run.originals[rel] = p.read_text("utf-8", errors="replace") if p.exists() else None

    async def _exec(self, run: AgentRun, name: str, a: dict) -> dict:
        ws = run.workspace
        if name == "set_phase":
            self.set_phase(run, a.get("phase", "PLAN"), a.get("note", ""))
            return {"ok": True}
        if name == "update_plan":
            run.plan = [str(s) for s in a.get("steps") or []]
            self.emit(run, "plan", {"steps": run.plan})
            return {"ok": True, "steps": len(run.plan)}
        if name == "list_files":
            if run.phase == "PLAN":
                self.set_phase(run, "READ")
            root = ws.resolve(a.get("path", "."))
            depth = min(int(a.get("depth") or 3), 8)
            out = []
            for p in sorted(root.rglob("*")):
                rel = p.relative_to(root)
                if len(rel.parts) > depth or any(x in SKIP_DIRS for x in rel.parts):
                    continue
                out.append(ws.rel(p) + ("/" if p.is_dir() else f" ({p.stat().st_size} B)"))
                if len(out) >= 800:
                    out.append("… (truncated)")
                    break
            return {"files": out or ["(empty)"]}
        if name == "read_file":
            if run.phase == "PLAN":
                self.set_phase(run, "READ")
            p = ws.resolve(a["path"])
            if not p.is_file():
                return {"error": f"File not found: {a['path']}"}
            lines = p.read_text("utf-8", errors="replace").splitlines()
            s = max(1, int(a.get("start_line") or 1))
            e = min(len(lines), int(a.get("end_line") or s + 599))
            body = "\n".join(f"{i:5d}| {lines[i - 1]}" for i in range(s, e + 1))
            return {"path": ws.rel(p), "total_lines": len(lines), "start_line": s, "end_line": e, "content": body}
        if name in ("write_file", "edit_file"):
            p = ws.resolve(a["path"])
            if self.settings["agent"]["require_approval_for_writes"]:
                if not await self._approval(run, "write", {"path": ws.rel(p), "summary": f"{name} {a['path']}"}):
                    return {"error": "User denied the write."}
            self._remember_original(run, p)
            if name == "write_file":
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(a["content"], "utf-8")
                res = {"ok": True, "path": ws.rel(p), "bytes": len(a["content"].encode())}
            else:
                if not p.is_file():
                    return {"error": f"File not found: {a['path']}"}
                src = p.read_text("utf-8", errors="replace")
                n = src.count(a["old"])
                if n != 1:
                    return {"error": f"'old' snippet found {n} times; it must match exactly once. Read the file and use a larger unique snippet."}
                p.write_text(src.replace(a["old"], a["new"], 1), "utf-8")
                res = {"ok": True, "path": ws.rel(p)}
            run.last_edit_at = now()
            self.set_phase(run, "DEBUG" if run.phase in ("TEST", "DEBUG") and any(r["exit_code"] for r in run.ledger[-1:]) else "IMPLEMENT")
            self.emit(run, "file_changed", {"path": ws.rel(p)})
            return res
        if name == "delete_file":
            p = ws.resolve(a["path"])
            if not p.exists():
                return {"error": "File not found"}
            if p.is_dir():
                return {"error": "delete_file only deletes files"}
            if self.settings["agent"]["require_approval_for_deletes"]:
                if not await self._approval(run, "delete", {"path": ws.rel(p), "reason": a.get("reason", "")}):
                    return {"error": "User denied the deletion."}
            self._remember_original(run, p)
            p.unlink()
            run.last_edit_at = now()
            self.emit(run, "file_changed", {"path": ws.rel(p), "deleted": True})
            return {"ok": True}
        if name == "search_code":
            rx = re.compile(a["pattern"], re.I)
            glob = a.get("glob") or "*"
            hits = []
            for p in ws.root.rglob("*"):
                if not p.is_file() or any(x in SKIP_DIRS for x in p.relative_to(ws.root).parts) or not fnmatch.fnmatch(p.name, glob):
                    continue
                if p.stat().st_size > 1_000_000:
                    continue
                try:
                    for i, line in enumerate(p.read_text("utf-8", errors="strict").splitlines(), 1):
                        if rx.search(line):
                            hits.append(f"{ws.rel(p)}:{i}: {line.strip()[:200]}")
                            if len(hits) >= 200:
                                return {"matches": hits, "truncated": True}
                except UnicodeDecodeError:
                    continue
            return {"matches": hits}
        if name == "run_command":
            cmd = str(a["command"]).strip()
            decision = classify_command(cmd, self.settings["agent"]["auto_approve_commands"])
            if decision.level == "blocked":
                self.db.audit("agent", "command.blocked", {"run": run.id, "cmd": cmd})
                return {"error": f"Command blocked by KYRO safety policy: {decision.reason}"}
            if decision.level == "approval":
                if not await self._approval(run, "command", {"command": cmd, "reason": decision.reason, "cwd": str(ws.root)}):
                    return {"error": "User denied this command."}
            kind = command_kind(cmd)
            if kind:
                self.set_phase(run, "VERIFY" if run.phase == "DEBUG" or (run.phase == "TEST" and run.ledger) else "TEST")
            timeout = min(int(a.get("timeout") or self.settings["agent"]["command_timeout"]), 3600)
            self.emit(run, "command_start", {"command": cmd})
            res = await asyncio.to_thread(ws.run, cmd, timeout)
            self.db.audit("agent", "command.run", {"run": run.id, "cmd": cmd, "exit": res["exit_code"]})
            if kind:
                run.ledger.append({"command": cmd, "kind": kind, "exit_code": res["exit_code"], "at": now()})
                if res["exit_code"] != 0:
                    self.set_phase(run, "DEBUG")
            self.emit(run, "command_output", {"command": cmd, **res})
            return res
        if name == "inspect_archive":
            from ..files import inspect_jar, jar_summary, zip_listing

            p = ws.resolve(a["path"])
            if p.suffix.lower() == ".jar":
                return {"summary": jar_summary(inspect_jar(p))}
            lst = zip_listing(p)
            return {"file_count": lst["file_count"], "files": lst["files"][:300]}
        if name == "finish":
            self.set_phase(run, "DELIVER")
            run.summary = a.get("summary", "") + (f"\n\nNext steps: {a['next_steps']}" if a.get("next_steps") else "")
            return {"ok": True, "verification": run.verification()}
        return {"error": f"Unknown tool {name}"}

    # ---------------------------------------------------------------- outputs
    def diffs(self, run_id: str) -> list[dict]:
        run = self.runs[run_id]
        out = []
        for rel, before in run.originals.items():
            p = run.workspace.root / rel
            after = p.read_text("utf-8", errors="replace") if p.exists() else None
            diff = "".join(difflib.unified_diff((before or "").splitlines(True), (after or "").splitlines(True),
                                                fromfile=f"a/{rel}" if before is not None else "/dev/null",
                                                tofile=f"b/{rel}" if after is not None else "/dev/null"))
            out.append({"path": rel, "status": "added" if before is None else "deleted" if after is None else "modified",
                        "diff": diff, "before": before, "after": after})
        return out

    def snapshot(self, run: AgentRun) -> dict:
        return {"id": run.id, "task": run.task, "status": run.status, "phase": run.phase, "plan": run.plan, "model": run.model,
                "workspace": str(run.workspace.root), "pending": run.pending, "verification": run.verification(),
                "summary": run.summary, "changed_files": list(run.originals.keys()), "project_id": run.project_id}


def zip_dir(root: Path) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for p in root.rglob("*"):
            rel = p.relative_to(root)
            if p.is_file() and not any(x in {".git", "node_modules", "__pycache__", ".venv", "venv"} for x in rel.parts):
                z.write(p, str(rel))
    return buf.getvalue()


def parse_fallback_tool_calls(text: str) -> list[dict]:
    """Accept ```tool {...}``` / ```json {"name":..,"arguments":..}``` blocks from models without native tools."""
    calls = []
    for m in re.finditer(r"```(?:tool|json)?\s*(\{.*?\})\s*```", text, re.S):
        try:
            d = json.loads(m.group(1))
        except json.JSONDecodeError:
            continue
        name = d.get("name") or d.get("tool")
        if name in {t["name"] for t in TOOLS}:
            calls.append({"id": "", "function": {"name": name, "arguments": d.get("arguments") or d.get("args") or {}}})
    return calls


def _preview_args(a: dict) -> dict:
    return {k: (v[:400] + f"… ({len(v)} chars)" if isinstance(v, str) and len(v) > 400 else v) for k, v in a.items()}


def _preview_result(r: dict) -> dict:
    s = json.dumps(r, default=str)
    if len(s) <= 3000:
        return r
    return {k: (v[:1500] + "…" if isinstance(v, str) and len(v) > 1500 else v) for k, v in r.items()} if isinstance(r, dict) else {"preview": s[:3000]}
