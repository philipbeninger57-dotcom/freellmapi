"""Chat pipeline test against an Ollama *protocol stub* (test double, used only here).

It verifies KYRO's plumbing — SSE streaming, memory RAG injection, persistence, titles, summarisation,
attachments — independent of model quality. Real-model tests live in test_integration_ollama.py."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from fastapi.testclient import TestClient

from kyro.main import create_app
from test_integration_ollama import parse_sse

RECEIVED: list[dict] = []


class StubOllama(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/api/version":
            return self._json({"version": "stub"})
        if self.path == "/api/tags":
            return self._json({"models": [{"name": "stub-chat:latest", "size": 1, "details": {"family": "qwen2"}}]})
        self._json({"error": "not found"}, 404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])) or b"{}")
        if self.path == "/api/embed":
            return self._json({"error": "model not found"}, 404)
        if self.path == "/api/chat":
            RECEIVED.append(body)
            if body["model"] != "stub-chat":
                return self._json({"error": f"model '{body['model']}' not found"}, 404)
            last = body["messages"][-1]["content"]
            reply = "Short title" if "short title" in last.lower() else ("SUMMARY" if "running summary" in last.lower() else f"echo: {last[:40]}")
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            for i in range(0, len(reply), 5):
                self.wfile.write((json.dumps({"message": {"role": "assistant", "content": reply[i:i + 5]}, "done": False}) + "\n").encode())
            self.wfile.write((json.dumps({"message": {"role": "assistant", "content": ""}, "done": True, "eval_count": 3}) + "\n").encode())
            return
        self._json({"error": "nope"}, 404)


@pytest.fixture
def stub_settings(settings):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubOllama)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    m = {r: "stub-chat" for r in ("chat", "reasoning", "coding", "vision", "summarize", "fast")}
    m["embedding"] = "none"
    settings.update({"ollama_url": f"http://127.0.0.1:{srv.server_port}", "models": m, "num_ctx": 2048})
    RECEIVED.clear()
    yield settings
    srv.shutdown()


def test_chat_stream_rag_persistence(stub_settings):
    with TestClient(create_app(stub_settings)) as c:
        # seed projects were loaded at startup
        projects = {p["name"]: p for p in c.get("/api/projects").json()}
        assert {"KYROSMP", "KYRO AntiCheat", "KYRO Client", "KYRO AI"} <= set(projects)
        pid = projects["KYRO AntiCheat"]["id"]
        conv = c.post("/api/conversations", json={"project_id": pid}).json()
        up = c.post("/api/files", files=[("files", ("Check.java", b"class SpeedCheck { double buffer = 3; }", "text/x-java"))]).json()
        r = c.post(f"/api/conversations/{conv['id']}/chat", json={"text": "How should the speed check avoid false positives?", "attachments": [up[0]["id"]]})
        ev = parse_sse(r.text)
        kinds = [k for k, _ in ev]
        assert kinds[0] == "meta" and "delta" in kinds and kinds[-1] == "done" and "title" in kinds
        meta = ev[0][1]
        assert meta["model"] == "stub-chat" and meta["role"] == "coding"
        assert any("False-positive" in m["content"] or "false" in m["content"].lower() for m in meta["memories"])
        system = RECEIVED[0]["messages"][0]["content"]
        assert "Project instructions" in system and "SpeedCheck" in system and "Relevant memories" in system
        answer = "".join(d["delta"] for k, d in ev if k == "delta")
        assert answer.startswith("echo: How should")
        full = c.get(f"/api/conversations/{conv['id']}").json()
        assert full["title"] == "Short title"
        assert full["messages"][1]["content"] == answer and full["messages"][0]["attachments"] == [up[0]["id"]]
        # regenerate replaces the last answer
        r2 = c.post(f"/api/conversations/{conv['id']}/chat", json={"regenerate": True})
        assert [k for k, _ in parse_sse(r2.text)][-1] == "done"
        assert len(c.get(f"/api/conversations/{conv['id']}").json()["messages"]) == 2
        # "remember that" stores an explicit memory
        c.post(f"/api/conversations/{conv['id']}/chat", json={"text": "Remember that my test server has 20 players."})
        assert c.get("/api/memories", params={"q": "20 players"}).json()["total"] == 1
        md = c.get(f"/api/conversations/{conv['id']}/export?fmt=md").text
        assert md.startswith("# Short title")


def test_long_conversation_is_summarised(stub_settings):
    with TestClient(create_app(stub_settings)) as c:
        conv = c.post("/api/conversations", json={}).json()
        for i in range(8):
            c.post(f"/api/conversations/{conv['id']}/chat", json={"text": f"message {i} " + "lorem ipsum dolor " * 120})
        metas = []
        r = c.post(f"/api/conversations/{conv['id']}/chat", json={"text": "final question"})
        metas = [d for k, d in parse_sse(r.text) if k == "meta"]
        full = c.get(f"/api/conversations/{conv['id']}").json()
        assert full["summary"] == "SUMMARY" and full["summary_upto"] > 0
        assert metas[0]["context_tokens"] < 2048


def test_import_endpoints_and_memory_api(stub_settings, tmp_path):
    with TestClient(create_app(stub_settings)) as c:
        r = c.post("/api/import/text", json={"text": "- Prefers German explanations\n- Uses Windows 11 with 32 GB RAM", "kind": "chatgpt_memory"}).json()
        assert r["memories_created"] == 2
        items = c.get("/api/memories", params={"source": "chatgpt-memory"}).json()["items"]
        assert {i["content"] for i in items} == {"Prefers German explanations", "Uses Windows 11 with 32 GB RAM"}
        mid = items[0]["id"]
        assert c.patch(f"/api/memories/{mid}", json={"content": "Prefers German or English explanations"}).json()["content"].startswith("Prefers German or")
        assert c.delete(f"/api/memories/{mid}").json()["ok"]
        exp = c.get("/api/memories/export?fmt=json").json()
        assert all(m["id"] != mid for m in exp["memories"])
        # round trip: export → wipe → re-import keeps kind/source/timestamp
        c.post("/api/memories", json={"content": "Main editor is IntelliJ IDEA", "kind": "preference"})
        dump = c.get("/api/memories/export?fmt=json").content
        for m in c.get("/api/memories", params={"limit": 1000}).json()["items"]:
            c.delete(f"/api/memories/{m['id']}")
        r = c.post("/api/import", files={"file": ("kyro-memories.json", dump, "application/json")}, data={"kind": "auto"}).json()
        assert r["memories_created"] >= 2
        again = c.get("/api/memories", params={"q": "IntelliJ"}).json()["items"][0]
        assert again["kind"] == "preference" and again["source"] == "manual"
        bad = c.post("/api/import", files={"file": ("x.json", b"{not json", "application/json")}, data={"kind": "auto"})
        assert bad.status_code == 400
        s = c.get("/api/settings").json()
        assert s["cloud"]["api_key"] == "" and s["data_dir"]


def test_auth_token(stub_settings):
    stub_settings.update({"auth": {"enabled": True}})
    tok = stub_settings["auth"]["token"]
    with TestClient(create_app(stub_settings)) as c:
        assert c.get("/api/projects").status_code == 401
        assert c.get("/api/projects", headers={"x-kyro-token": tok}).status_code == 200
        assert c.get("/api/health").status_code == 200
