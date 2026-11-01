import os
import sys
from pathlib import Path

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from kyro.config import Settings  # noqa: E402

OLLAMA = os.environ.get("KYRO_TEST_OLLAMA", "http://127.0.0.1:11434")
TEST_CHAT_MODEL = os.environ.get("KYRO_TEST_MODEL", "qwen2.5:1.5b")
TEST_EMBED_MODEL = os.environ.get("KYRO_TEST_EMBED", "all-minilm")


def ollama_models() -> set[str]:
    try:
        r = httpx.get(f"{OLLAMA}/api/tags", timeout=2, trust_env=False)
        return {m["name"] for m in r.json()["models"]}
    except Exception:
        return set()


def ollama_up() -> bool:
    try:
        return httpx.get(f"{OLLAMA}/api/version", timeout=2, trust_env=False).status_code == 200
    except Exception:
        return False


def has_model(name: str) -> bool:
    ms = ollama_models()
    return name in ms or f"{name}:latest" in ms


requires_ollama_server = pytest.mark.skipif(not ollama_up(), reason="Ollama server not running")
requires_ollama = pytest.mark.skipif(not has_model(TEST_CHAT_MODEL), reason=f"Ollama with {TEST_CHAT_MODEL} not available")
requires_embed = pytest.mark.skipif(not has_model(TEST_EMBED_MODEL), reason=f"Ollama with {TEST_EMBED_MODEL} not available")


@pytest.fixture
def settings(tmp_path):
    s = Settings(tmp_path / "data")
    m = {r: TEST_CHAT_MODEL for r in ("chat", "reasoning", "coding", "vision", "summarize", "fast")}
    m["embedding"] = "kyro-no-such-embed-model"  # default: lexical fallback (deterministic tests)
    s.update({"ollama_url": OLLAMA, "models": m, "num_ctx": 4096})
    return s
