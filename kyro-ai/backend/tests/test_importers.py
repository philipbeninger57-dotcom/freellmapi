import json
import zipfile

import pytest

from kyro.importers import (detect_json_kind, parse_chatgpt_conversations, parse_claude_conversations,
                            parse_claude_projects, parse_memory_list)
from kyro.memory import extract_candidates, is_grounded

CHATGPT = [{
    "title": "Plugin help", "create_time": 1700000000.0, "update_time": 1700000500.0, "conversation_id": "c-1",
    "current_node": "n3",
    "mapping": {
        "root": {"id": "root", "message": None, "parent": None, "children": ["n1"]},
        "n1": {"id": "n1", "parent": "root", "children": ["n2", "n2b"], "message": {
            "author": {"role": "user"}, "create_time": 1700000001.0,
            "content": {"content_type": "text", "parts": ["I prefer Java 21 for all my plugins. How do I use the Folia scheduler?"]}}},
        "n2b": {"id": "n2b", "parent": "n1", "children": [], "message": {
            "author": {"role": "assistant"}, "create_time": 1700000002.0, "content": {"content_type": "text", "parts": ["ABANDONED BRANCH"]}}},
        "n2": {"id": "n2", "parent": "n1", "children": ["n3"], "message": {
            "author": {"role": "assistant"}, "create_time": 1700000003.0, "content": {"content_type": "text", "parts": ["Use the RegionScheduler."]}}},
        "n3": {"id": "n3", "parent": "n2", "children": [], "message": {
            "author": {"role": "user"}, "create_time": 1700000004.0, "content": {"content_type": "text", "parts": ["Thanks!"]}}},
    },
}]

CLAUDE = [{
    "uuid": "u-1", "name": "Anticheat", "created_at": "2024-05-01T10:00:00Z", "updated_at": "2024-05-01T11:00:00Z",
    "chat_messages": [
        {"uuid": "m1", "sender": "human", "created_at": "2024-05-01T10:00:00Z", "text": "",
         "content": [{"type": "text", "text": "My server runs Folia 1.21.11. Remember that false positives are worse than missed cheats."}]},
        {"uuid": "m2", "sender": "assistant", "created_at": "2024-05-01T10:00:05Z", "text": "Understood."},
    ],
}]


def test_chatgpt_follows_active_branch():
    convs = parse_chatgpt_conversations(CHATGPT)
    assert len(convs) == 1
    texts = [m["content"] for m in convs[0]["messages"]]
    assert texts == ["I prefer Java 21 for all my plugins. How do I use the Folia scheduler?", "Use the RegionScheduler.", "Thanks!"]
    assert "ABANDONED BRANCH" not in texts
    assert convs[0]["messages"][0]["time"] == 1700000001.0


def test_claude_conversation_parsing_and_timestamps():
    convs = parse_claude_conversations(CLAUDE)
    assert convs[0]["title"] == "Anticheat"
    assert convs[0]["messages"][0]["role"] == "user"
    assert "Folia 1.21.11" in convs[0]["messages"][0]["content"]
    assert convs[0]["created"] == pytest.approx(1714557600.0)


def test_detect_kinds():
    assert detect_json_kind("conversations.json", CHATGPT) == "chatgpt_conversations"
    assert detect_json_kind("conversations.json", CLAUDE) == "claude_conversations"
    assert detect_json_kind("projects.json", [{"uuid": "x", "name": "p", "prompt_template": "", "docs": []}]) == "claude_projects"
    assert detect_json_kind("memories.json", [{"content": "x"}]) == "memories"


def test_memory_list_shapes():
    assert [m["content"] for m in parse_memory_list("- Likes dark mode\n- Uses Windows 11\n# heading\n")] == ["Likes dark mode", "Uses Windows 11"]
    assert len(parse_memory_list({"memories": [{"content": "a fact"}, "b fact"]})) == 2
    assert parse_memory_list([{"memory": "Works on KYROSMP", "created_at": "2024-01-01T00:00:00Z"}])[0]["time"] is not None


def test_claude_projects():
    p = parse_claude_projects([{"uuid": "p1", "name": "KYRO AntiCheat", "prompt_template": "Be strict.", "docs": [{"filename": "a.md", "content": "x"}]}])
    assert p[0]["instructions"] == "Be strict." and p[0]["docs"][0]["filename"] == "a.md"


def test_extraction_is_verbatim_and_skips_questions():
    c = extract_candidates("I prefer Java 21 for all my plugins. How do I use the Folia scheduler? My name is Philip.")
    sents = [s for s, _ in c]
    assert "I prefer Java 21 for all my plugins." in sents
    assert "My name is Philip." in sents
    assert all(not s.endswith("?") for s in sents)


def test_grounding_gate():
    src = "I mostly write Paper plugins in Java 21 and build them with Maven."
    assert is_grounded("User writes Paper plugins in Java 21 with Maven", src)
    assert not is_grounded("User lives in Berlin and owns a cat", src)


@pytest.mark.asyncio
async def test_full_import_zip_dedup_and_conflicts(settings, tmp_path):
    from kyro.main import Services

    S = Services(settings)
    z = tmp_path / "export.zip"
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("conversations.json", json.dumps(CHATGPT))
    r = await S.importer.import_file(z, "chatgpt-export.zip")
    assert r["conversations"] == 1 and r["messages"] == 3
    assert r["memories_created"] >= 1
    # import again: conversation replaced (not duplicated) and memories deduplicated
    r2 = await S.importer.import_file(z, "chatgpt-export.zip")
    assert r2["memories_created"] == 0 and r2["duplicates"] >= 1
    assert S.db.one("SELECT COUNT(*) n FROM conversations WHERE source='chatgpt'")["n"] == 1
    mem = S.memory.list(source="chatgpt")["items"][0]
    assert mem["source_time"] == 1700000001.0  # original timestamp preserved
    # Claude export with the same facts from another source
    cz = tmp_path / "claude.json"
    cz.write_text(json.dumps(CLAUDE))
    r3 = await S.importer.import_file(cz, "conversations.json")
    assert r3["conversations"] == 1
    hits = await S.memory.search("Which Folia version does my server run?")
    assert any("1.21.11" in h["content"] for h in hits)


@pytest.mark.asyncio
async def test_split_export_zip_any_filenames(settings, tmp_path):
    """Newer exports split data into conversations-000.zip with arbitrarily named / sharded files."""
    from kyro.main import Services

    S = Services(settings)
    single = dict(CLAUDE[0], uuid="u-single", name="Single file conv")
    variant = {"id": "v-1", "title": "Messages variant", "created_at": "2025-01-01T00:00:00Z",
               "messages": [{"role": "user", "content": "I prefer Gradle for KYRO Client builds."},
                            {"role": "assistant", "content": "Okay."}]}
    z = tmp_path / "conversations-000.zip"
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("data/conversations-000.json", json.dumps(CLAUDE))
        f.writestr("data/conv/u-single.json", json.dumps(single))
        f.writestr("data/shard.jsonl", json.dumps(variant) + "\n")
        f.writestr("metadata.json", json.dumps({"account": {"id": "x"}}))  # ignored, not an error
    r = await S.importer.import_file(z, "conversations-000.zip")
    assert r["conversations"] == 3
    titles = {c["title"] for c in S.db.all("SELECT title FROM conversations WHERE source='claude'")}
    assert titles == {"Anticheat", "Single file conv", "Messages variant"}
    assert any("Gradle" in m["content"] for m in S.memory.list(source="claude")["items"])


@pytest.mark.asyncio
async def test_metadata_only_zip_and_manifest_give_helpful_errors(settings, tmp_path):
    from kyro.main import Services

    S = Services(settings)
    meta = tmp_path / "light_metadata-000.zip"
    with zipfile.ZipFile(meta, "w") as f:
        f.writestr("light_metadata.json", json.dumps([{"uuid": "a", "size": 3}]))
    with pytest.raises(ValueError, match="conversations-000.zip"):
        await S.importer.import_file(meta, "light_metadata-000.zip")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"version": "1.0", "total_files": 1, "data_files": [
        {"export_url": "https://example.invalid/download/x", "category": "conversations", "filename": "conversations-000.zip"}]}))
    with pytest.raises(ValueError, match="only the export manifest"):
        await S.importer.import_file(manifest, "manifest.json")
