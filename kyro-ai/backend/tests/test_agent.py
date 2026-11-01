"""Agent loop mechanics. A scripted provider (test double) replays tool calls so the loop, sandbox,
approvals and the verification ledger can be tested deterministically without a model."""
import asyncio
import sys

import pytest

from kyro.agent.loop import AgentEngine, parse_fallback_tool_calls
from kyro.db import Database
from kyro.llm import ChatChunk, ModelRouter

PY = f'"{sys.executable}"'


class ScriptedProvider:
    name = "scripted"

    def __init__(self, steps):
        self.steps = list(steps)
        self.seen = []

    async def chat(self, model, messages, *, tools=None, options=None, think=None):
        self.seen.append(messages[-1])
        calls = self.steps.pop(0) if self.steps else [("finish", {"summary": "nothing left"})]
        yield ChatChunk(tool_calls=[{"id": f"c{i}", "function": {"name": n, "arguments": a}} for i, (n, a) in enumerate(calls)], done=True)


def engine(settings, provider):
    class ScriptedRouter(ModelRouter):  # inject the test double without touching the real class
        @property
        def ollama(self):
            return provider

    return AgentEngine(Database(settings.db_path), ScriptedRouter(settings), settings)


async def wait(run, status=("done", "failed", "cancelled")):
    for _ in range(400):
        if run.status in status and (run.task_handle.done() or run.status == "waiting_approval"):
            return
        await asyncio.sleep(0.02)
    raise AssertionError(f"timeout, status={run.status}")


@pytest.mark.asyncio
async def test_agent_write_test_fix_verify(settings):
    settings.update({"agent": {"auto_approve_commands": [sys.executable, PY]}})
    prov = ScriptedProvider([
        [("set_phase", {"phase": "PLAN"}), ("update_plan", {"steps": ["write", "test"]})],
        [("write_file", {"path": "calc.py", "content": "def add(a, b):\n    return a - b\n"})],
        [("write_file", {"path": "test_calc.py", "content": "from calc import add\nassert add(2, 3) == 5, 'bad add'\nprint('ok')\n"})],
        [("run_command", {"command": f"{PY} test_calc.py"})],                       # fails -> DEBUG
        [("edit_file", {"path": "calc.py", "old": "a - b", "new": "a + b"})],
        [("run_command", {"command": f"{PY} test_calc.py"})],                       # passes -> verified
        [("finish", {"summary": "Implemented add()"})],
    ])
    eng = engine(settings, prov)
    run = eng.start("make add work", workspace="t1")
    await wait(run)
    assert run.status == "done", run.events[-3:]
    v = run.verification()
    assert v["status"] == "verified"
    assert [r["exit_code"] for r in run.ledger] == [1, 0]
    phases = [e["phase"] for e in run.events if e["type"] == "phase"]
    for p in ("PLAN", "IMPLEMENT", "TEST", "DEBUG", "VERIFY", "DELIVER"):
        assert p in phases or p == "DELIVER"
    diffs = {d["path"]: d for d in eng.diffs(run.id)}
    assert diffs["calc.py"]["status"] == "added" and "a + b" in diffs["calc.py"]["after"]


@pytest.mark.asyncio
async def test_agent_unverified_when_edit_after_test(settings):
    settings.update({"agent": {"auto_approve_commands": [PY]}})
    prov = ScriptedProvider([
        [("write_file", {"path": "a.py", "content": "print(1)\n"})],
        [("run_command", {"command": f"{PY} a.py"})],
        [("write_file", {"path": "a.py", "content": "print(2)\n"})],
        [("finish", {"summary": "done, all tests pass"})],   # claim is NOT accepted as verification
    ])
    run = engine(settings, prov).start("x", workspace="t2")
    await wait(run)
    # 'python a.py' is not a build/test command -> nothing verified at all
    assert run.verification()["status"] == "not_verified"


@pytest.mark.asyncio
async def test_agent_approval_and_sandbox(settings):
    prov = ScriptedProvider([
        [("read_file", {"path": "../../etc/passwd"})],
        [("run_command", {"command": "echo hello > out.txt"})],   # redirection -> approval required
        [("run_command", {"command": "rm -rf /"})],               # blocked
        [("finish", {"summary": "ok"})],
    ])
    eng = engine(settings, prov)
    run = eng.start("x", workspace="t3")
    await wait(run, ("waiting_approval",))
    assert run.pending["kind"] == "command"
    eng.approve(run.id, False)
    await wait(run)
    results = [e for e in run.events if e["type"] == "tool_result"]
    assert "outside the workspace" in results[0]["result"]["error"]
    assert "denied" in results[1]["result"]["error"]
    assert "blocked" in results[2]["result"]["error"].lower()
    assert not (run.workspace.root / "out.txt").exists()


def test_fallback_tool_parsing():
    calls = parse_fallback_tool_calls('Sure.\n```tool\n{"name": "read_file", "arguments": {"path": "a.txt"}}\n```')
    assert calls[0]["function"] == {"name": "read_file", "arguments": {"path": "a.txt"}}
    assert parse_fallback_tool_calls('```json\n{"name": "rm_everything"}\n```') == []
