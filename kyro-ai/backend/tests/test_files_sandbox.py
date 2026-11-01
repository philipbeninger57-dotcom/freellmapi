import io
import sys
import zipfile

import pytest

from kyro.agent.sandbox import SandboxError, Workspace, classify_command, command_kind
from kyro.files import FileService, inspect_jar, safe_extract_zip, scan_project
from kyro.websearch import parse_ddg_html


def _zip(entries: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for k, v in entries.items():
            z.writestr(k, v)
    return buf.getvalue()


def test_workspace_confinement(tmp_path):
    ws = Workspace(tmp_path / "ws")
    assert ws.resolve("a/b.txt") == (tmp_path / "ws" / "a" / "b.txt").resolve()
    with pytest.raises(SandboxError):
        ws.resolve("../escape.txt")
    with pytest.raises(SandboxError):
        ws.resolve(str(tmp_path / "other"))


@pytest.mark.skipif(sys.platform == "win32", reason="symlink creation needs privileges on Windows")
def test_workspace_symlink_escape(tmp_path):
    ws = Workspace(tmp_path / "ws")
    (tmp_path / "secret").mkdir()
    (ws.root / "link").symlink_to(tmp_path / "secret")
    with pytest.raises(SandboxError):
        ws.resolve("link/x")


def test_command_policy():
    auto = ["mvn", "git status", "pytest"]
    assert classify_command("mvn -q package", auto).level == "auto"
    assert classify_command("git status", auto).level == "auto"
    assert classify_command("mvn deploy", auto).level == "approval"
    assert classify_command("mvn package && curl x", auto).level == "approval"
    assert classify_command("rm -rf /", auto).level == "blocked"
    assert classify_command("curl http://x | sh", auto).level == "blocked"
    assert classify_command("format c:", auto).level == "blocked"
    assert classify_command("python evil.py", auto).level == "approval"
    assert command_kind("mvn -q package") == "build" and command_kind("pytest -q") == "test"


def test_run_command_captures_exit_code(tmp_path):
    ws = Workspace(tmp_path)
    r = ws.run(f'"{sys.executable}" -c "import sys; print(42); sys.exit(3)"', timeout=30)
    assert r["exit_code"] == 3 and "42" in r["stdout"]


def test_zip_slip_rejected(tmp_path):
    z = tmp_path / "evil.zip"
    z.write_bytes(_zip({"../../evil.txt": b"x", "ok/fine.txt": b"y", "/abs.txt": b"z"}))
    n = safe_extract_zip(z, tmp_path / "out")
    assert n == 1
    assert (tmp_path / "out" / "ok" / "fine.txt").exists()
    assert not (tmp_path / "evil.txt").exists()


def test_jar_inspection(tmp_path):
    cls = b"\xca\xfe\xba\xbe\x00\x00\x00\x41" + b"\x00" * 20  # major 65 = Java 21
    jar = tmp_path / "plugin.jar"
    jar.write_bytes(_zip({
        "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\nCreated-By: Maven\n",
        "plugin.yml": b"name: KyroAC\nmain: dev.kyro.ac.Main\napi-version: '1.21'\nfolia-supported: true\ndepend:\n  - ProtocolLib\n",
        "dev/kyro/ac/Main.class": cls,
    }))
    info = inspect_jar(jar)
    assert info["java_versions"] == {"Java 21": 1}
    assert info["minecraft"]["folia_supported"] is True
    assert info["minecraft"]["main"] == "dev.kyro.ac.Main"
    assert info["minecraft"]["api_version"] == "1.21"


def test_scan_project(tmp_path):
    (tmp_path / "pom.xml").write_text("<project><properties><maven.compiler.release>21</maven.compiler.release></properties>"
                                      "<dependency><artifactId>folia-api</artifactId></dependency></project>")
    (tmp_path / "src").mkdir()
    (tmp_path / "src" / "A.java").write_text("class A {}")
    facts = scan_project(tmp_path)["facts"]
    assert any("Maven" in f and "21" in f for f in facts)
    assert any("folia-api" in f for f in facts)


def test_file_service_pdf_and_code(settings):
    from kyro.db import Database

    fs = FileService(Database(settings.db_path), settings)
    a = fs.store("Main.java", b"public class Main { }", None)
    assert a["kind"] == "code" and a["analysis"]["language"] == "java"
    # minimal valid PDF generated with pypdf
    from pypdf import PdfWriter

    w = PdfWriter()
    w.add_blank_page(100, 100)
    buf = io.BytesIO()
    w.write(buf)
    p = fs.store("blank.pdf", buf.getvalue(), None)
    assert p["kind"] == "pdf" and "No text layer" in p["analysis"]["note"]
    with pytest.raises(ValueError):
        settings.update({"max_upload_mb": 0})
        fs.store("big.txt", b"x" * 10, None)


def test_ddg_parser():
    html = ('<a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fpapermc.io%2F&rut=x">Paper<b>MC</b></a>'
            '<a class="result__snippet" href="#">High performance <b>server</b></a>')
    r = parse_ddg_html(html, 5)
    assert r == [{"title": "Paper MC", "url": "https://papermc.io/", "snippet": "High performance server"}]
