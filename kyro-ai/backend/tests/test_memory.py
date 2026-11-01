import json

import pytest

from kyro.embeddings import cosine, hash_embed
from kyro.main import Services
from kyro.memory import contradiction_signal


def test_hash_embed_similarity_ordering():
    a = hash_embed("I build Minecraft plugins with Maven")
    b = hash_embed("Minecraft plugins are built with Maven")
    c = hash_embed("My favourite food is pizza")
    assert cosine(a, b) > cosine(a, c)


def test_contradiction_signals():
    assert contradiction_signal("I use Java 17", "I use Java 21")
    assert contradiction_signal("I like tabs", "I don't like tabs")
    assert contradiction_signal("The server runs on Folia", "The server runs on Paper")
    assert contradiction_signal("I like Rust", "I like Rust") is None


@pytest.mark.asyncio
async def test_crud_dedup_conflict_scope(settings):
    S = Services(settings)
    pid = S.db.one("SELECT id FROM projects LIMIT 1")
    from kyro.importers import ensure_project
    pid = ensure_project(S.db, "TestProj")

    r1 = await S.memory.add("I use Java 17 for my plugins", source="chatgpt", source_time=1000)
    assert r1.status == "created"
    dup = await S.memory.add("I use Java 17 for my plugins.", source="claude", source_time=2000)
    assert dup.status == "duplicate" and dup.duplicate_of == r1.id
    m = S.memory.get(r1.id)
    assert m["extra_sources"][0]["source"] == "claude"  # other source preserved

    r2 = await S.memory.add("I use Java 21 for my plugins", source="claude", source_time=3000)
    assert r2.status == "conflict"
    assert S.memory.get(r1.id)["status"] == "conflict"
    hits = await S.memory.search("which Java version for plugins")
    assert hits[0]["id"] == r2.id  # newer memory wins; older surfaced as conflict note
    assert hits[0]["conflict_note"][0]["id"] == r1.id
    S.memory.resolve_conflict(r2.id, r1.id, "keep_one")
    assert S.memory.get(r1.id)["status"] == "superseded"
    assert S.memory.get(r2.id)["status"] == "active"

    # project scope separation
    p = await S.memory.add("The anticheat uses a 3 tick buffer for speed checks", scope="project", project_id=pid)
    gen = await S.memory.search("speed check tick buffer")
    assert all(h["id"] != p.id for h in gen)
    proj = await S.memory.search("speed check tick buffer", project_id=pid)
    assert proj and proj[0]["id"] == p.id

    upd = await S.memory.update(p.id, {"content": "The anticheat uses a 4 tick buffer for speed checks", "pinned": True})
    assert "4 tick" in upd["content"] and upd["pinned"]
    data = json.loads(S.memory.export("json"))
    assert len(data["memories"]) == 3
    assert S.memory.delete(p.id)
    assert S.memory.get(p.id) is None
    md = S.memory.export("md")
    assert "Java 21" in md


@pytest.mark.asyncio
async def test_never_returns_unrelated_when_empty(settings):
    S = Services(settings)
    assert await S.memory.search("anything") == []
