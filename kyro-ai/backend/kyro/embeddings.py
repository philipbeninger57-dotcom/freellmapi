"""Embeddings for semantic memory search.

Primary: an Ollama embedding model (default `nomic-embed-text`).
Fallback: `hash-lex-512`, a deterministic local feature-hashing vector (word unigrams +
bigrams + character trigrams, TF-weighted, L2-normalised). It is a *lexical* similarity
measure, not a neural semantic one, and it is labelled as such everywhere it is used. It lets
memory search keep working when no embedding model is installed yet. Vectors from different
models are never compared; `reembed_all` migrates stored vectors when you change model.
"""
from __future__ import annotations

import hashlib
import math
import re

import numpy as np

from .llm import LLMError, ModelRouter

FALLBACK_MODEL = "hash-lex-512"
_DIM = 512


def hash_embed(text: str) -> np.ndarray:
    v = np.zeros(_DIM, dtype=np.float32)
    t = text.lower()
    words = re.findall(r"[a-z0-9äöüß_]+", t)
    feats = words + [f"{a} {b}" for a, b in zip(words, words[1:])]
    compact = " ".join(words)
    feats += [compact[i:i + 3] for i in range(max(0, len(compact) - 2))]
    for f in feats:
        h = int.from_bytes(hashlib.blake2b(f.encode(), digest_size=8).digest(), "little")
        v[h % _DIM] += (1.0 if (h >> 32) & 1 else -1.0) * (1.5 if " " in f and len(f) > 3 else 1.0)
    n = float(np.linalg.norm(v))
    return v / n if n else v


def to_blob(v) -> bytes:
    return np.asarray(v, dtype=np.float32).tobytes()


def from_blob(b: bytes | None) -> np.ndarray | None:
    return None if not b else np.frombuffer(b, dtype=np.float32)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    if a is None or b is None or a.shape != b.shape:
        return 0.0
    na, nb = float(np.linalg.norm(a)), float(np.linalg.norm(b))
    return float(np.dot(a, b) / (na * nb)) if na and nb else 0.0


class Embedder:
    def __init__(self, router: ModelRouter):
        self.router = router
        self._neural_ok: bool | None = None  # cached availability; reset via reset()

    def reset(self) -> None:
        self._neural_ok = None

    @property
    def configured_model(self) -> str:
        return self.router.settings["models"].get("embedding") or ""

    async def active_model(self) -> str:
        if self._neural_ok is None and self.configured_model:
            try:
                await self.router.ollama.embed(self.configured_model, ["ping"])
                self._neural_ok = True
            except Exception:  # noqa: BLE001
                self._neural_ok = False
        return self.configured_model if self._neural_ok else FALLBACK_MODEL

    async def embed(self, texts: list[str]) -> tuple[list[np.ndarray], str]:
        model = await self.active_model()
        if model == FALLBACK_MODEL:
            return [hash_embed(t) for t in texts], FALLBACK_MODEL
        out: list[np.ndarray] = []
        try:
            for i in range(0, len(texts), 32):
                batch = [t[:8000] for t in texts[i:i + 32]]
                out += [np.asarray(v, dtype=np.float32) for v in await self.router.ollama.embed(model, batch)]
            return out, model
        except LLMError:
            self._neural_ok = False
            return [hash_embed(t) for t in texts], FALLBACK_MODEL

    async def embed_one(self, text: str) -> tuple[np.ndarray, str]:
        vs, m = await self.embed([text])
        return vs[0], m


def chunk_text(text: str, size: int = 1200, overlap: int = 150) -> list[str]:
    text = text.strip()
    if len(text) <= size:
        return [text] if text else []
    paras = re.split(r"\n\s*\n", text)
    chunks, cur = [], ""
    for p in paras:
        while len(p) > size:
            if cur:
                chunks.append(cur)
                cur = ""
            chunks.append(p[:size])
            p = p[size - overlap:]
        if len(cur) + len(p) + 2 > size and cur:
            chunks.append(cur)
            cur = cur[-overlap:] + "\n\n" + p if overlap else p
        else:
            cur = f"{cur}\n\n{p}" if cur else p
    if cur.strip():
        chunks.append(cur)
    return chunks


def approx_tokens(text: str) -> int:
    return math.ceil(len(text) / 3.6)
