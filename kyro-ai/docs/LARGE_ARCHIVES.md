# KYRO AI 0.2: large conversation archives

KYRO stores original messages on disk and retrieves relevant excerpts when answering.
Importing an archive does not train model weights or turn historical assistant answers
into verified facts. All processing is local; Ollama is optional for import and keyword search.

**Updating an existing Windows installation**

1. Stop KYRO. Back up the entire `%USERPROFILE%\.kyro-ai` directory while it is stopped
   (or your custom data directory), including any SQLite WAL files.
2. Update/download `kyro-ai` from the same GitHub branch. Keep your existing data directory.
3. Run `install.bat -Models none` in a terminal in `kyro-ai` to install the new `ijson`
   dependency and rebuild the frontend. This keeps an existing configuration and skips model downloads.
4. Start `start.bat`. The database additions are created automatically.
5. On **Import**, click **Index existing conversations** once. This creates a resumable
   derived index for messages imported by older versions; originals remain in place.

**Importing through the interface**

Choose **Large conversation archive**, optionally choose an existing project, and upload
JSON, JSONL/NDJSON or an export ZIP. Upload completes first; then a background worker
imports the retained local copy in batches. You can leave the page and return to see
processed/added/updated/duplicate/skipped counts. Counts describe records/messages,
not a guessed percentage of total input size.

Pause commits the current batch. Resume continues after the last committed checkpoint,
including after a server restart. Recovery replays the input stream up to that checkpoint;
it remains memory bounded but may take time for a large compressed ZIP. Failed jobs keep
their upload and committed data; fix a structural input error by supplying a corrected file.
Removing a job deletes its retained upload and progress, and keeps its imported messages.
Completed uploads are removed automatically. Small OS lock files remain for process coordination.

The large importer handles conversation data only. For Claude project documents, memory
lists, custom instructions and repository ZIPs, use the corresponding standard import type.
Non-conversation objects in mixed exports are counted as skipped. ChatGPT imports follow
the active branch, matching the standard importer; alternate branches are not imported.

**JSONL format for custom datasets**

Write one object per line, encoded as UTF-8. For example, the following are two lines:

```jsonl
{"source":"my-archive","conversation_id":"thread-42","id":"message-1","role":"user","content":"We use Java 21 for KYRO AntiCheat.","timestamp":"2026-10-01T12:00:00Z"}
{"source":"my-archive","conversation_id":"thread-42","id":"message-2","role":"assistant","content":"Use the entity scheduler for player access.","timestamp":"2026-10-01T12:00:05Z"}
```

Use stable `source`, `conversation_id` and message `id` values. Reimporting the same IDs
skips unchanged messages and updates changed text. Identical text from different sources
or different message IDs is retained as distinct historical evidence. Without IDs, fallback
IDs depend on the upload/record location; use explicit IDs for split or evolving datasets.
Roles `user`, `assistant`, `system` and `tool` are supported for flat message records;
historical system/tool text is also treated as quoted data, never as runtime instructions.

ChatGPT/Claude conversation objects can also be written one per JSONL line, in a JSON
array, in a `conversations`/`items`/`data` array wrapper, or as a single conversation object.
Source IDs and original timestamps are retained. Existing conversations cannot silently
move between general/project scopes on reimport: select their original scope, or move
them explicitly in KYRO before importing again.

**Local-file import from PowerShell**

For multi-GB files you can skip the browser upload. From `kyro-ai\backend`:

```powershell
.\.venv\Scripts\python.exe -m kyro.bulk_import "D:\Exports\messages.jsonl"
.\.venv\Scripts\python.exe -m kyro.bulk_import "D:\Exports\messages.jsonl" --project "KYRO AntiCheat"
.\.venv\Scripts\python.exe -m kyro.bulk_import --resume JOB_ID
```

`Ctrl+C` requests a pause after a batch. The CLI uses the same data directory as KYRO;
pass `--data-dir PATH` if your installation uses a custom location. Use one web server
process. OS job locks prevent the CLI and web application from importing the same job
at once, and pause requests are persisted across processes.

**Retrieval and resource limits**

- Text is chunked once into roughly 1,800-character excerpts with 180-character overlap.
  The index stores message/conversation links; changes and deletion invalidate derived chunks/vectors.
- FTS5 searches the permitted scope before limiting candidates: general chats see general
  sources; a project sees its own sources plus general sources.
- With Ollama available, up to `archive.candidate_limit` keyword candidates are reranked
  semantically. Their embeddings are cached in SQLite, with a versioned model space.
  Nomic query/document prefixes are applied. A new question still requires a query embedding.
- This is **keyword retrieval plus semantic reranking**, not global ANN/vector retrieval.
  A source with no matching keyword is not guaranteed to be found by a paraphrase. Check
  retrieval using your own questions and expected sources before trusting a large archive.
- Archive excerpts enter a shared context budget with attachments, memories and chat history.
  Source labels `[A1]` etc. open the historical message in a paginated conversation view.
  Historical assistant content may be wrong; displayed sources show the author role and date.
- A JSON conversation object or JSONL line is limited to approximately 32 MiB. Split a huge
  single conversation into flat message JSONL. JSON arrays/ZIPs are streamed, not loaded as a whole.
- The configured upload and expanded ZIP limit defaults to 20 GiB. The ZIP entry limit is
  100,000. Free disk is still required for the original upload, messages and search indexes.
  Change `archive.max_import_gb` in your local configuration if needed.

Defaults in `kyro.config.json` are merged with existing settings:

```json
{
  "archive": {
    "auto_retrieve": true,
    "top_k": 6,
    "candidate_limit": 48,
    "semantic_rerank": true,
    "context_tokens": 1200,
    "batch_size": 500,
    "max_import_gb": 20
  }
}
```

**Reproducible scale check**

From `kyro-ai`, run the following to create and import synthetic short messages in a
temporary directory. No user data or model is used. The temporary files are removed afterward.
Reported speed, RSS (where available) and disk usage belong to that machine and dataset.

```powershell
.\backend\.venv\Scripts\python.exe scripts\benchmark_archive.py --messages 1000000
```

Long messages, more chunks, different disks and simultaneous model inference change the results.
This benchmark checks storage/retrieval mechanics; it does not measure answer quality.
