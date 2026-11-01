"""Workspace sandbox for the coding agent.

What it enforces
* File tools can only touch paths inside the run's workspace (symlink escapes are resolved and rejected).
* Commands run with cwd = workspace, a timeout, a scrubbed environment and truncated output.
* Every command is classified: `auto` (matches your auto-approve list and has no shell chaining/redirection),
  `approval` (anything else) or `blocked` (catastrophic patterns that are never run).

What it does NOT do: this is a policy sandbox, not OS-level isolation. An approved command runs with your user's
permissions. Review approval requests. For stronger isolation run KYRO inside a VM/Windows Sandbox/Docker.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


class SandboxError(Exception):
    pass


BLOCKED = [
    r"\brm\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)\s+(/|~|\*|[a-z]:\\?)\s*$",
    r"\bformat\s+[a-z]:", r"\bmkfs\b", r"\bdd\s+if=.*of=/dev/", r":\(\)\s*\{\s*:\|:&\s*\};:",
    r"\bshutdown\b", r"\breboot\b", r"\bdiskpart\b", r"\bbcdedit\b", r"\breg\s+delete\b",
    r"\b(curl|wget|iwr|invoke-webrequest)\b[^|]*\|\s*(sh|bash|iex|invoke-expression|python|powershell)\b",
    r"-encodedcommand\b|\s-enc\s", r"\bdel\s+/[sq].*\s[a-z]:\\\s*$", r"\bcipher\s+/w", r"\bvssadmin\b",
    r"\bnet\s+user\b", r"\bschtasks\b.*\/create",
]
DESTRUCTIVE = [
    r"\brm\b", r"\bdel\b", r"\brmdir\b", r"\brd\b", r"\bremove-item\b", r"\bgit\s+(push|reset\s+--hard|clean|checkout\s+--|rebase|branch\s+-D)",
    r"\bmv\b", r"\bmove\b", r"\bnpm\s+publish\b", r"\bmvn\s+deploy\b", r"\bpip\s+uninstall\b", r"\bchmod\b", r"\bchown\b",
]
SHELL_META = re.compile(r"[;&|><`]|\$\(")


@dataclass
class CommandDecision:
    level: str  # auto | approval | blocked
    reason: str


def classify_command(cmd: str, auto_prefixes: list[str]) -> CommandDecision:
    c = cmd.strip()
    low = c.lower()
    for p in BLOCKED:
        if re.search(p, low):
            return CommandDecision("blocked", f"matches blocked pattern ({p})")
    for p in DESTRUCTIVE:
        if re.search(p, low):
            return CommandDecision("approval", "potentially destructive command")
    if SHELL_META.search(c):
        return CommandDecision("approval", "uses shell chaining/redirection")
    for pre in auto_prefixes:
        pl = pre.lower().strip()
        if low == pl or low.startswith(pl + " "):
            return CommandDecision("auto", f"auto-approved prefix '{pre}'")
    return CommandDecision("approval", "not in auto-approve list")


class Workspace:
    def __init__(self, root: Path):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def resolve(self, rel: str | os.PathLike) -> Path:
        rel = str(rel or ".").strip()
        p = Path(rel)
        target = (p if p.is_absolute() else self.root / p).resolve()
        if target != self.root and self.root not in target.parents:
            raise SandboxError(f"Path '{rel}' is outside the workspace {self.root}")
        return target

    def rel(self, p: Path) -> str:
        return str(p.resolve().relative_to(self.root)).replace("\\", "/") or "."

    def run(self, cmd: str, timeout: int = 300, max_output: int = 30000) -> dict:
        env = {k: v for k, v in os.environ.items() if not re.search(r"(TOKEN|SECRET|PASSWORD|API_KEY|KYRO_AUTH)", k, re.I)}
        env["KYRO_SANDBOX"] = "1"
        # Agent edits can happen within the same second with equal file size; stale .pyc caches would then
        # run old code and produce misleading test results.
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        try:
            proc = subprocess.run(cmd, shell=True, cwd=self.root, env=env, capture_output=True, timeout=timeout,
                                  stdin=subprocess.DEVNULL,
                                  creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if sys.platform == "win32" else 0)
            out = proc.stdout.decode("utf-8", errors="replace")
            err = proc.stderr.decode("utf-8", errors="replace")
            code = proc.returncode
            timed_out = False
        except subprocess.TimeoutExpired as e:
            out = (e.stdout or b"").decode("utf-8", errors="replace")
            err = (e.stderr or b"").decode("utf-8", errors="replace") + f"\n[timed out after {timeout}s]"
            code, timed_out = -1, True

        def trunc(s: str) -> str:
            if len(s) <= max_output:
                return s
            half = max_output // 2
            return s[:half] + f"\n…[{len(s) - max_output} chars truncated]…\n" + s[-half:]

        return {"exit_code": code, "stdout": trunc(out), "stderr": trunc(err), "timed_out": timed_out}


TEST_CMD = re.compile(r"\b(test|tests|pytest|unittest|jest|vitest|mocha|junit|cargo test|go test|dotnet test|ctest|check|verify)\b"
                      r"|(^|[\s/\\\"'])test_\w+|\w+_test\.\w+|\.(test|spec)\.\w+", re.I)
BUILD_CMD = re.compile(r"\b(build|compile|javac|tsc|mvn|maven|gradle|gradlew|cargo build|go build|make|cmake|dotnet build|package|install)\b", re.I)


def command_kind(cmd: str) -> str | None:
    if TEST_CMD.search(cmd):
        return "test"
    if BUILD_CMD.search(cmd):
        return "build"
    return None
