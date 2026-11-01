"""Secure upload processing: type detection, text extraction, archive inspection.

Security: uploads are stored under <data>/uploads with random names; original names are only
metadata. Archives are never executed. Extraction rejects absolute paths, `..` traversal,
symlinks, and oversized/over-compressed archives (zip bombs).
"""
from __future__ import annotations

import base64
import io
import json
import mimetypes
import re
import shutil
import zipfile
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree

from .db import Database, new_id, now
from .embeddings import chunk_text

TEXT_EXT = {
    ".txt", ".md", ".markdown", ".rst", ".log", ".csv", ".tsv", ".json", ".yml", ".yaml", ".toml", ".ini", ".cfg",
    ".conf", ".properties", ".xml", ".html", ".htm", ".css", ".scss", ".sql", ".env.example", ".gitignore",
}
CODE_EXT = {
    ".java": "java", ".kt": "kotlin", ".kts": "kotlin", ".gradle": "groovy", ".groovy": "groovy", ".py": "python",
    ".js": "javascript", ".mjs": "javascript", ".cjs": "javascript", ".jsx": "javascript", ".ts": "typescript",
    ".tsx": "typescript", ".c": "c", ".h": "c", ".cpp": "cpp", ".cc": "cpp", ".hpp": "cpp", ".cs": "csharp",
    ".rs": "rust", ".go": "go", ".rb": "ruby", ".php": "php", ".swift": "swift", ".lua": "lua", ".sh": "shell",
    ".bat": "bat", ".ps1": "powershell", ".html": "html", ".css": "css", ".vue": "vue", ".svelte": "svelte",
    ".sk": "skript", ".mcfunction": "mcfunction",
}
IMAGE_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}
MAX_ZIP_TOTAL = 2_000_000_000
MAX_ZIP_RATIO = 200
MAX_ZIP_FILES = 50_000


def classify(filename: str) -> str:
    n = filename.lower()
    ext = Path(n).suffix
    if ext in IMAGE_EXT:
        return "image"
    if ext == ".pdf":
        return "pdf"
    if ext == ".jar":
        return "jar"
    if ext == ".zip":
        return "zip"
    if ext == ".docx":
        return "document"
    if ext in CODE_EXT:
        return "code"
    if ext in TEXT_EXT or n.endswith((".env.example",)):
        return "text"
    return "other"


def pdf_text(path: Path, max_pages: int = 500) -> str:
    from pypdf import PdfReader

    r = PdfReader(str(path))
    out = []
    for i, page in enumerate(r.pages[:max_pages]):
        try:
            t = page.extract_text() or ""
        except Exception:  # noqa: BLE001 - damaged page, keep going
            t = ""
        if t.strip():
            out.append(f"--- page {i + 1} ---\n{t}")
    return "\n\n".join(out)


def docx_text(path: Path) -> str:
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml")
    ns = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    root = ElementTree.fromstring(xml)
    paras = []
    for p in root.iter(f"{ns}p"):
        paras.append("".join(t.text or "" for t in p.iter(f"{ns}t")))
    return "\n".join(paras)


def _check_zip(z: zipfile.ZipFile, archive_size: int) -> list[zipfile.ZipInfo]:
    infos = z.infolist()
    if len(infos) > MAX_ZIP_FILES:
        raise ValueError(f"Archive has {len(infos)} entries (limit {MAX_ZIP_FILES})")
    total = sum(i.file_size for i in infos)
    if total > MAX_ZIP_TOTAL:
        raise ValueError("Archive expands beyond 2 GB")
    if archive_size and total / max(archive_size, 1) > MAX_ZIP_RATIO and total > 50_000_000:
        raise ValueError("Suspicious compression ratio (possible zip bomb)")
    return infos


def safe_member_path(name: str) -> PurePosixPath | None:
    p = PurePosixPath(name.replace("\\", "/"))
    if p.is_absolute() or ".." in p.parts or (p.parts and re.match(r"^[A-Za-z]:", p.parts[0])):
        return None
    return p


def safe_extract_zip(path: Path, dest: Path) -> int:
    dest = dest.resolve()
    dest.mkdir(parents=True, exist_ok=True)
    n = 0
    with zipfile.ZipFile(path) as z:
        for info in _check_zip(z, path.stat().st_size):
            rel = safe_member_path(info.filename)
            mode = (info.external_attr >> 16) & 0o170000
            if rel is None or mode == 0o120000:  # traversal or symlink
                continue
            target = (dest / rel).resolve()
            if not str(target).startswith(str(dest)):
                continue
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
            n += 1
    return n


def zip_listing(path: Path, limit: int = 2000) -> dict:
    with zipfile.ZipFile(path) as z:
        infos = _check_zip(z, path.stat().st_size)
        files = [{"name": i.filename, "size": i.file_size} for i in infos if not i.is_dir()]
        texts = {}
        for i in infos:
            if i.is_dir() or i.file_size > 200_000 or not safe_member_path(i.filename):
                continue
            kind = classify(i.filename)
            if kind in ("code", "text") and len(texts) < 60:
                texts[i.filename] = z.read(i).decode("utf-8", errors="replace")
        return {"file_count": len(files), "total_size": sum(f["size"] for f in files), "files": files[:limit], "texts": texts}


def inspect_jar(path: Path) -> dict:
    """Static JAR inspection: manifest, plugin descriptors, packages, class versions. Nothing is executed."""
    info: dict = {"manifest": {}, "descriptors": {}, "classes": 0, "packages": {}, "java_versions": {}, "resources": []}
    with zipfile.ZipFile(path) as z:
        for i in _check_zip(z, path.stat().st_size):
            n = i.filename
            if n == "META-INF/MANIFEST.MF":
                for line in z.read(i).decode("utf-8", errors="replace").splitlines():
                    if ":" in line:
                        k, v = line.split(":", 1)
                        info["manifest"][k.strip()] = v.strip()
            elif n in ("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json", "fabric.mod.json",
                       "META-INF/mods.toml", "mcmod.info", "quilt.mod.json"):
                info["descriptors"][n] = z.read(i).decode("utf-8", errors="replace")[:20000]
            elif n.endswith(".class"):
                info["classes"] += 1
                pkg = "/".join(n.split("/")[:-1]).replace("/", ".") or "(default)"
                info["packages"][pkg] = info["packages"].get(pkg, 0) + 1
                if info["classes"] <= 400:
                    head = z.open(i).read(8)
                    if head[:4] == b"\xca\xfe\xba\xbe":
                        major = int.from_bytes(head[6:8], "big")
                        jv = f"Java {major - 44}" if major >= 49 else f"major {major}"
                        info["java_versions"][jv] = info["java_versions"].get(jv, 0) + 1
            elif not i.is_dir() and len(info["resources"]) < 300:
                info["resources"].append(n)
    info["packages"] = dict(sorted(info["packages"].items(), key=lambda x: -x[1])[:80])
    d = info["descriptors"]
    if "plugin.yml" in d or "paper-plugin.yml" in d:
        y = d.get("paper-plugin.yml") or d["plugin.yml"]
        info["minecraft"] = {
            "type": "Paper plugin" if "paper-plugin.yml" in d else "Bukkit/Spigot/Paper plugin",
            "folia_supported": bool(re.search(r"^folia-supported:\s*true", y, re.M)),
            "api_version": (re.search(r"^api-version:\s*['\"]?([^'\"\n]+)", y, re.M) or [None, None])[1],
            "main": (re.search(r"^main:\s*(\S+)", y, re.M) or [None, None])[1],
            "depends": re.findall(r"^\s*-\s*(\S+)", y.split("depend", 1)[1], re.M)[:30] if "depend" in y else [],
        }
    elif "fabric.mod.json" in d:
        info["minecraft"] = {"type": "Fabric mod"}
    elif "META-INF/mods.toml" in d:
        info["minecraft"] = {"type": "Forge/NeoForge mod"}
    return info


def jar_summary(info: dict) -> str:
    lines = [f"JAR: {info['classes']} classes; bytecode: {info['java_versions']}"]
    if info["manifest"]:
        lines.append("Manifest: " + ", ".join(f"{k}={v}" for k, v in list(info["manifest"].items())[:12]))
    if info.get("minecraft"):
        lines.append(f"Minecraft: {json.dumps(info['minecraft'])}")
    for k, v in info["descriptors"].items():
        lines.append(f"--- {k} ---\n{v[:4000]}")
    lines.append("Top packages: " + ", ".join(f"{k} ({v})" for k, v in list(info["packages"].items())[:25]))
    return "\n".join(lines)


def ocr_image(path: Path) -> str | None:
    """Optional OCR via Tesseract (pytesseract + tesseract binary). Returns None if unavailable."""
    try:
        import pytesseract
        from PIL import Image

        return pytesseract.image_to_string(Image.open(path))
    except Exception:  # noqa: BLE001
        return None


def image_b64(path: Path, max_side: int = 1568) -> str:
    """Base64 for vision models; downscales large images when Pillow is installed."""
    try:
        from PIL import Image

        im = Image.open(path)
        if max(im.size) > max_side:
            im.thumbnail((max_side, max_side))
            buf = io.BytesIO()
            im.convert("RGB").save(buf, format="JPEG", quality=90)
            return base64.b64encode(buf.getvalue()).decode()
    except Exception:  # noqa: BLE001 - Pillow optional
        pass
    return base64.b64encode(path.read_bytes()).decode()


def scan_project(root: Path, max_files: int = 20000) -> dict:
    """Derive verifiable facts from a project tree (build system, languages, Minecraft metadata)."""
    root = root.resolve()
    langs: dict[str, int] = {}
    files = []
    for p in root.rglob("*"):
        if any(part in {".git", "node_modules", "target", "build", ".gradle", "out", "dist", "__pycache__", ".idea"} for part in p.parts):
            continue
        if p.is_file():
            files.append(p)
            lang = CODE_EXT.get(p.suffix.lower())
            if lang:
                langs[lang] = langs.get(lang, 0) + 1
            if len(files) >= max_files:
                break
    rel = {str(f.relative_to(root)).replace("\\", "/") for f in files}
    names = {Path(r).name for r in rel}
    facts = [f"Project root: {root.name}; {len(files)} files; languages: " + ", ".join(f"{k} ({v})" for k, v in sorted(langs.items(), key=lambda x: -x[1])[:8])]
    if "pom.xml" in names:
        pom = next(f for f in files if f.name == "pom.xml").read_text("utf-8", errors="replace")
        jv = re.search(r"<(?:maven\.compiler\.(?:release|source)|java\.version|release)>\s*([\d.]+)", pom)
        facts.append("Build system: Maven (pom.xml)" + (f"; Java version {jv.group(1)}" if jv else ""))
        for dep in ("paper-api", "folia-api", "spigot-api", "floodgate-api", "geyser-api", "luckperms", "packetevents", "protocollib"):
            if dep in pom.lower():
                facts.append(f"Maven dependency present: {dep}")
    if names & {"build.gradle", "build.gradle.kts"}:
        g = next(f for f in files if f.name in ("build.gradle", "build.gradle.kts")).read_text("utf-8", errors="replace")
        facts.append("Build system: Gradle")
        if "fabric-loom" in g or "fabric" in g.lower():
            facts.append("Gradle build uses Fabric (fabric-loom)")
        jv = re.search(r"JavaLanguageVersion\.of\((\d+)\)|sourceCompatibility\s*=\s*['\"]?(?:JavaVersion\.VERSION_)?([\d_.]+)", g)
        if jv:
            facts.append(f"Gradle Java version: {jv.group(1) or jv.group(2)}")
    if "package.json" in names:
        facts.append("Node.js project (package.json)")
    if names & {"pyproject.toml", "requirements.txt"}:
        facts.append("Python project")
    if "Cargo.toml" in names:
        facts.append("Rust project (Cargo)")
    for f in files:
        if f.name in ("plugin.yml", "paper-plugin.yml"):
            y = f.read_text("utf-8", errors="replace")
            main = re.search(r"^main:\s*(\S+)", y, re.M)
            api = re.search(r"^api-version:\s*['\"]?([^'\"\n]+)", y, re.M)
            folia = "true" if re.search(r"^folia-supported:\s*true", y, re.M) else "false"
            facts.append(f"{f.name}: main={main.group(1) if main else '?'}, api-version={api.group(1) if api else '?'}, "
                         f"folia-supported={folia}")
    readme = next((f for f in files if f.name.lower() in ("readme.md", "readme.txt")), None)
    if readme:
        txt = readme.read_text("utf-8", errors="replace").strip()
        facts.append("README excerpt: " + re.sub(r"\s+", " ", txt[:600]))
    return {"facts": facts, "languages": langs, "file_count": len(files), "files": sorted(rel)[:500]}


class FileService:
    def __init__(self, db: Database, settings):
        self.db = db
        self.settings = settings

    def store(self, filename: str, data: bytes, mime: str | None = None) -> dict:
        limit = int(self.settings["max_upload_mb"]) * 1024 * 1024
        if len(data) > limit:
            raise ValueError(f"File exceeds the {self.settings['max_upload_mb']} MB upload limit")
        safe = re.sub(r"[^\w.\-]+", "_", Path(filename).name)[-120:] or "file"
        aid = new_id()
        path = self.settings.uploads_dir / f"{aid}_{safe}"
        path.write_bytes(data)
        kind = classify(filename)
        mime = mime or mimetypes.guess_type(filename)[0] or "application/octet-stream"
        text, analysis = "", {}
        try:
            text, analysis = self._analyze(path, filename, kind)
        except Exception as e:  # noqa: BLE001 - keep the file; report the analysis error clearly
            analysis = {"error": f"Could not analyse file: {e.__class__.__name__}: {e}"}
        self.db.execute(
            "INSERT INTO attachments(id,filename,stored_path,mime,size,kind,extracted_text,analysis,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            (aid, filename, str(path), mime, len(data), kind, text, json.dumps(analysis), now()))
        return self.get(aid)

    def _analyze(self, path: Path, filename: str, kind: str) -> tuple[str, dict]:
        if kind == "image":
            ocr = ocr_image(path)
            return (ocr or ""), {"ocr": "tesseract" if ocr is not None else "unavailable (vision model will read the image)"}
        if kind == "pdf":
            t = pdf_text(path)
            return t, {"chars": len(t), "note": "" if t.strip() else "No text layer found (scanned PDF?). Install Tesseract for OCR."}
        if kind == "document":
            t = docx_text(path)
            return t, {"chars": len(t)}
        if kind in ("code", "text"):
            t = path.read_bytes()[:2_000_000].decode("utf-8", errors="replace")
            return t, {"language": CODE_EXT.get(Path(filename).suffix.lower(), "text"), "lines": t.count("\n") + 1}
        if kind == "jar":
            info = inspect_jar(path)
            return jar_summary(info), {"jar": info}
        if kind == "zip":
            lst = zip_listing(path)
            body = "\n".join(f"{f['name']} ({f['size']} B)" for f in lst["files"][:400])
            for name, content in list(lst["texts"].items())[:30]:
                body += f"\n\n--- {name} ---\n{content[:6000]}"
            return body, {"file_count": lst["file_count"], "total_size": lst["total_size"]}
        return "", {"note": "Binary file stored; no text extracted."}

    def get(self, aid: str) -> dict | None:
        r = self.db.one("SELECT * FROM attachments WHERE id=?", (aid,))
        if not r:
            return None
        r["analysis"] = json.loads(r["analysis"])
        r["preview_text"] = r["extracted_text"][:4000]
        r.pop("stored_path", None)
        return r

    def path(self, aid: str) -> Path | None:
        r = self.db.one("SELECT stored_path FROM attachments WHERE id=?", (aid,))
        return Path(r["stored_path"]) if r else None

    def full(self, aid: str) -> dict | None:
        return self.db.one("SELECT * FROM attachments WHERE id=?", (aid,))

    def chunks_for(self, aid: str) -> list[str]:
        r = self.full(aid)
        return chunk_text(r["extracted_text"]) if r else []
