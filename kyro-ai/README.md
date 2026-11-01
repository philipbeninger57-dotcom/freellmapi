# KYRO AI

Your own local, private AI assistant: chat, reasoning, coding agent, vision, documents, research, and one
unified long-term memory built from **your exported ChatGPT and Claude data**.

- **Unlimited local use.** Inference runs on [Ollama](https://ollama.com). No accounts, quotas, or subscriptions.
  Your hardware, context length, and speed are the only limits.
- **Unified memory.** Import ChatGPT and Claude exports, memory lists, custom instructions, Claude projects, notes,
  and code projects. KYRO merges duplicates, flags contradictions, keeps every source and timestamp, and retrieves
  relevant memories automatically (RAG).
- **Coding agent.** It runs the loop PLAN → READ → IMPLEMENT → TEST → DEBUG → VERIFY → DELIVER in a sandboxed
  workspace, asks your approval before risky commands, shows diffs, and marks work as *verified* only when a
  build or test command actually exited with code 0.
- **Premium UI.** React, TypeScript, Tailwind, Monaco. Dark and light themes, Markdown, LaTeX, code highlighting,
  live HTML/SVG previews, file drag & drop, a terminal, and a project explorer.

> KYRO does not contain or reproduce any proprietary Claude or ChatGPT models, weights, or internal systems. It
> runs open-weight models locally. It only reads data you export and upload yourself; it never connects to your
> ChatGPT or Claude accounts.

---

## Quick start (Windows 10/11)

1. Download or clone this folder (`kyro-ai`).
2. Double-click **`install.bat`**. It runs `scripts/windows/install.ps1`, which:
   - checks Windows version, RAM, GPU and disk space
   - installs Python 3.12, Node.js LTS and Ollama with `winget` if they are missing
   - creates `backend\.venv`, installs the Python packages, then builds the UI (`npm ci && npm run build`)
   - writes `%USERPROFILE%\.kyro-ai\kyro.config.json` with a model profile that fits your RAM
   - downloads the models (`ollama pull …`) and creates a desktop shortcut

   Options: `install.bat -Models light` (16 GB RAM), `-Models none` (skip downloads), `-WithJava` (Temurin
   JDK 21 + Maven for Minecraft plugin builds), `-NoWinget` (report missing tools instead of installing them).
3. Start KYRO with **`start.bat`** or the desktop shortcut. It makes sure Ollama is running, then opens
   <http://127.0.0.1:8765>.

You can re-run the installer safely. Its log is written to `install.log`. If something fails, see
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

### Manual installation (any OS)

```bash
# 1. Ollama: https://ollama.com/download, then
ollama pull qwen3:14b && ollama pull qwen2.5-coder:14b && ollama pull qwen2.5vl:7b && ollama pull nomic-embed-text
# 2. Backend
cd backend && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt   # Linux/macOS: .venv/bin/pip
# 3. Frontend
cd ../frontend && npm ci && npm run build
# 4. Run
cd ../backend && .venv/Scripts/python -m kyro --open     # or ./start.sh on Linux/macOS
```

Development mode with hot reload: `scripts\windows\dev.ps1` (backend on :8765, Vite on :5173).

---

## Importing your ChatGPT and Claude knowledge

| Source | How to get it | What KYRO does |
|---|---|---|
| ChatGPT conversations | Settings → Data controls → Export data. Upload the ZIP. | Walks the active branch of every conversation, stores it as searchable history, and extracts first-person facts and preferences verbatim |
| ChatGPT memory | Settings → Personalization → Manage memories. Copy the list and paste it on the Import page. | One memory per line, marked as verified |
| Claude conversations and projects | Settings → Privacy → Export data. Upload the ZIP. | Conversations plus attachments; projects become KYRO projects with their instructions and docs as project memory |
| Claude memory / project instructions | Copy, then paste or upload a .txt | Stored as instructions or facts |
| Custom instructions, notes, PDFs, DOCX | Upload or paste | Chunked into notes |
| Code projects | Upload a ZIP of the repo with type “Programming project” | Safe extraction into the project workspace, plus derived facts (build system, Java version, Paper/Folia descriptors, …) |

**Memory guarantees**

- Original source, reference and timestamp are kept for every memory. When the same fact appears in another
  source, that source is recorded on the existing memory instead of creating a duplicate.
- Near-duplicates are found with embeddings plus a content hash.
- Possible contradictions (negation, different versions or values) are flagged on both memories. Retrieval prefers
  the newer one and tells the model about the conflict. You resolve it on the Memory page with *keep this*,
  *keep other*, or *both true*.
- Nothing is invented. Rule-based extraction stores only verbatim sentences from your own messages. The optional
  LLM extraction is grounding-checked against your original words, and ungrounded facts are rejected and counted.
- Project memories and general memories are kept separate. A chat in a project sees both; a chat outside any
  project sees only general memories.
- You can inspect, edit, pin, verify, export (JSON or Markdown), re-import, and delete memories.

The initial context for **KYROSMP, KYRO AntiCheat, KYRO Client and KYRO AI** is seeded from
[`seed/projects.json`](seed/projects.json) on first start. Edit or delete it like any other memory.

---

## Features

| Area | What you get |
|---|---|
| Chat modes | Chat, Reasoning (thinking stream), Coding, Project, Research (web and citations), Vision, Document |
| Model routing | `Auto` picks chat, coding, reasoning or vision models by task, attachments and mode. Any installed or cloud model can be chosen manually. |
| Long context | Automatic rolling summarisation of older turns. Large documents are reduced to the most relevant chunks by embeddings. |
| History | Every conversation (KYRO plus imported) in SQLite with FTS5 full-text search, pinning, project folders, and Markdown/JSON export |
| Files | Images (vision models, optional Tesseract OCR), PDF text, DOCX, ZIP listing and content, static JAR inspection (manifest, plugin.yml, Folia flag, bytecode Java version), code files, multi-file drag & drop and paste |
| Artifacts | HTML and SVG code blocks render live in a sandboxed iframe. Code blocks can be copied or downloaded with detected filenames. |
| Coding agent | Tools: plan, list, read, write, edit, delete (approval), regex search, run command (policy-checked), inspect archives, finish. Shows live events, approval prompts, a phase stepper, a verification badge, Monaco diffs, and a ZIP download. |
| Code workspace | File explorer, Monaco editor (bundled offline), save with Ctrl+S, terminal panel, project folders on your disk |
| Web search | DuckDuckGo (no key) or your own SearXNG. Pages are fetched and cited as [n]. Private/loopback URLs are blocked. |
| Images | Optional local generation and img2img editing through ComfyUI |
| Cloud (optional) | Any OpenAI-compatible endpoint, such as FreeLLMAPI (this repo), OpenRouter, LM Studio or vLLM. Never required. |
| Personalities | Editable personalities and global custom instructions |
| Security | Binds to 127.0.0.1 by default, optional access token, workspace path confinement, blocked catastrophic commands, approvals, scrubbed secrets in the command environment, zip-slip and zip-bomb protection, audit log |

---

## Architecture

```
frontend/ (React + TS + Tailwind + Monaco)  ──HTTP/SSE──▶  backend/kyro (FastAPI)
                                                          ├─ chat.py        context assembly, RAG, summaries, streaming
                                                          ├─ memory.py      unified memory: dedup, conflicts, hybrid search
                                                          ├─ importers.py   ChatGPT / Claude / notes / project ZIP
                                                          ├─ files.py       secure upload analysis (PDF, DOCX, ZIP, JAR, images)
                                                          ├─ llm.py         Ollama + OpenAI-compatible providers, routing
                                                          ├─ embeddings.py  Ollama embeddings (+ labelled lexical fallback)
                                                          ├─ agent/         sandbox + PLAN→…→DELIVER loop
                                                          ├─ websearch.py   search + fetch with citations
                                                          └─ imagegen.py    ComfyUI client
                                                                │
                                         SQLite (WAL, FTS5) ◀───┘───▶ Ollama (local models)
```

- Data lives in `%USERPROFILE%\.kyro-ai` (override with `KYRO_DATA_DIR`): `kyro.db`, `kyro.config.json`,
  `uploads\`, `workspace\`, `generated\`.
- Vector search is an exact cosine scan over float32 vectors stored in SQLite, combined with FTS5 BM25 keyword
  scores. That is fast for tens of thousands of memories. Larger collections would need an ANN index (see limitations).
- The API is documented at <http://127.0.0.1:8765/docs> (OpenAPI).

---

## Testing and verification status

```
scripts\windows\test.ps1        # or: cd backend && pytest -q ; cd frontend && npx tsc -b && npx vitest run
```

What was verified while building (Linux CI container):

- **Backend: 31 pytest tests pass; 4 more are skipped because they need real models (see below).** They cover the importers (ChatGPT branch walking, Claude parsing,
  memory-list shapes, ZIP import, re-import dedup), memory (dedup with source merge, conflict flagging and
  resolution, scope separation, edit, export, delete), sandbox (path and symlink escape, command policy, zip-slip,
  JAR inspection, project scan), agent mechanics (write → failing test → DEBUG → fix → passing test = *verified*;
  editing after a test = not verified; approvals; blocked commands), the chat pipeline (SSE, RAG injection, project
  instructions, attachments, titles, regenerate, "remember that…", automatic summarisation), the import API, and
  the access token.
- **Against a real Ollama server (v0.40):** health, model listing, and the clear "model not installed → `ollama pull`"
  error.
- **Frontend:** TypeScript strict type-check, 5 vitest tests (SSE parser, artifact detection), and the production
  build. The UI was driven end-to-end in Chromium (Playwright): chat, memory add and search, import, Monaco
  edit/save, terminal, settings, projects, light/dark theme, mobile layout, streamed Markdown/LaTeX/code, the live
  artifact preview, and a full agent run with approval, test, verification badge and diff review.

**Not verified here:** answers from real models. The build container's network policy blocks the Ollama
model registry and Hugging Face, so the model-dependent tests (`test_integration_ollama.py`: real streaming chat
with RAG, neural embedding search, a real agent run) were **skipped**, not faked. They run automatically on your PC
once you install the two small test models:

```
ollama pull qwen2.5:1.5b
ollama pull all-minilm
scripts\windows\test.ps1
```

The pipeline UI tests used an Ollama *protocol stub*, which exists only in the test suite. KYRO itself never
produces canned answers. The Windows scripts were syntax-checked with the PowerShell 7 parser but not executed on
Windows.

## Known limitations

- Quality and speed depend on the local model. On a 32 GB RAM PC without a strong GPU, 14B models produce roughly
  3–8 tokens/s. Use the `light` profile or a GPU for faster replies. Local models are capable but not equal to the
  largest proprietary models.
- The sandbox is a policy sandbox: workspace confinement, approvals and blocked patterns. An *approved* command
  runs with your Windows user rights. For hard isolation, run KYRO inside Windows Sandbox or a VM.
- Agent runs are kept in memory. After a server restart their history stays in the database, but live
  approval/diff data is gone.
- OCR of images and scanned PDFs needs Tesseract (`winget install UB-Mannheim.TesseractOCR` and
  `pip install pytesseract`). Otherwise the vision model reads images directly.
- Image generation requires a separately installed ComfyUI with a checkpoint.
- Memory search is an exact scan. Past roughly 200k memories, an ANN index (e.g. sqlite-vec) would be needed.
- The heuristic contradiction detector finds negation, number/version and value changes. Subtle semantic
  contradictions can be missed.
