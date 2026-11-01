"""KYRO AI configuration.

Settings are layered: built-in defaults < kyro.config.json in the data dir < environment
variables (KYRO_*). The settings API writes changes back to kyro.config.json.
"""
from __future__ import annotations

import json
import os
import secrets
import threading
from copy import deepcopy
from pathlib import Path
from typing import Any

DEFAULTS: dict[str, Any] = {
    "host": "127.0.0.1",
    "port": 8765,
    # Local inference (Ollama). No quotas: hardware is the only limit.
    "ollama_url": "http://127.0.0.1:11434",
    # Model roles. "auto" routing picks one of these per request.
    # Defaults are sized for a 32 GB RAM Windows PC (see docs/MODELS.md).
    "models": {
        "chat": "qwen3:14b",
        "reasoning": "deepseek-r1:14b",
        "coding": "qwen2.5-coder:14b",
        "vision": "qwen2.5vl:7b",
        "embedding": "nomic-embed-text",
        "summarize": "qwen3:14b",
        "fast": "qwen3:4b",
    },
    "num_ctx": 16384,
    # Optional cloud / OpenAI-compatible endpoint (e.g. FreeLLMAPI, OpenRouter). Never required.
    "cloud": {"enabled": False, "base_url": "", "api_key": "", "models": []},
    # Optional services
    "searxng_url": "",
    "comfyui_url": "",
    "web_search_enabled": True,
    # Agent sandbox
    "workspace_root": "",  # default: <data_dir>/workspace
    "agent": {
        "max_steps": 40,
        "command_timeout": 300,
        # Commands whose first token is listed here run without asking. Everything else
        # (and anything destructive) waits for explicit approval in the UI.
        "auto_approve_commands": [
            "git status", "git diff", "git log", "ls", "dir", "mvn", "gradle", "./gradlew",
            "gradlew", "npm test", "npm run build", "pytest", "python -m pytest", "cargo build",
            "cargo test", "go build", "go test", "javac", "java -version", "dotnet build",
            "dotnet test", "tsc",
        ],
        "require_approval_for_writes": False,
        "require_approval_for_deletes": True,
    },
    "memory": {
        "auto_retrieve": True,
        "top_k": 8,
        "min_score": 0.35,
        "duplicate_threshold": 0.93,
        "conflict_threshold": 0.78,
        "auto_extract_from_chats": False,
    },
    "custom_instructions": "",
    "personalities": {
        "default": "You are KYRO AI, a precise, honest and capable personal assistant. "
        "Be direct. If you are unsure, say so. Never invent facts about the user.",
        "mentor": "You are KYRO AI in mentor mode: explain reasoning step by step and teach.",
        "concise": "You are KYRO AI. Answer as briefly as possible while staying correct.",
    },
    "active_personality": "default",
    "auth": {"enabled": False, "token": ""},
    "max_upload_mb": 200,
}

ENV_MAP = {
    "KYRO_HOST": ("host", str),
    "KYRO_PORT": ("port", int),
    "KYRO_OLLAMA_URL": ("ollama_url", str),
    "KYRO_WORKSPACE": ("workspace_root", str),
    "KYRO_SEARXNG_URL": ("searxng_url", str),
    "KYRO_COMFYUI_URL": ("comfyui_url", str),
}


def _merge(base: dict, override: dict) -> dict:
    out = deepcopy(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


class Settings:
    def __init__(self, data_dir: str | os.PathLike | None = None):
        self.data_dir = Path(data_dir or os.environ.get("KYRO_DATA_DIR") or Path.home() / ".kyro-ai").resolve()
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.config_path = self.data_dir / "kyro.config.json"
        self._lock = threading.RLock()
        self._data: dict[str, Any] = {}
        self.reload()

    def reload(self) -> None:
        with self._lock:
            data = deepcopy(DEFAULTS)
            if self.config_path.exists():
                try:
                    data = _merge(data, json.loads(self.config_path.read_text("utf-8")))
                except json.JSONDecodeError as e:  # keep running with defaults, but tell the user
                    print(f"[kyro] WARNING: {self.config_path} is invalid JSON ({e}); using defaults")
            for env, (key, cast) in ENV_MAP.items():
                if os.environ.get(env):
                    data[key] = cast(os.environ[env])
            if data["auth"]["enabled"] and not data["auth"]["token"]:
                data["auth"]["token"] = secrets.token_urlsafe(32)
                self._data = data
                self.save()
            self._data = data

    def save(self) -> None:
        with self._lock:
            tmp = self.config_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self._data, indent=2), "utf-8")
            tmp.replace(self.config_path)

    def update(self, patch: dict) -> dict:
        with self._lock:
            self._data = _merge(self._data, patch)
            if self._data["auth"]["enabled"] and not self._data["auth"]["token"]:
                self._data["auth"]["token"] = secrets.token_urlsafe(32)
            self.save()
            return self.public()

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)

    def __getitem__(self, key: str) -> Any:
        return self._data[key]

    @property
    def raw(self) -> dict:
        return self._data

    def public(self) -> dict:
        """Settings safe to show in the UI (secrets masked)."""
        d = deepcopy(self._data)
        if d["cloud"].get("api_key"):
            d["cloud"]["api_key"] = "********"
        d["auth"]["token"] = "********" if d["auth"].get("token") else ""
        d["data_dir"] = str(self.data_dir)
        d["workspace_root_resolved"] = str(self.workspace_root)
        return d

    @property
    def workspace_root(self) -> Path:
        p = Path(self._data.get("workspace_root") or self.data_dir / "workspace").expanduser().resolve()
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def uploads_dir(self) -> Path:
        p = self.data_dir / "uploads"
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def generated_dir(self) -> Path:
        p = self.data_dir / "generated"
        p.mkdir(parents=True, exist_ok=True)
        return p

    @property
    def db_path(self) -> Path:
        return self.data_dir / "kyro.db"
