# Troubleshooting and error recovery

| Symptom | Fix |
|---|---|
| `install.bat` closes immediately | Run it from a terminal: `powershell -ExecutionPolicy Bypass -File scripts\windows\install.ps1`, then read `install.log`. |
| "winget is not available" | Install *App Installer* from the Microsoft Store, or install Python 3.12, Node.js LTS and Ollama manually and re-run with `-NoWinget`. |
| Python, Node or Ollama "not found" right after installing | Open a **new** terminal so PATH refreshes, then re-run the installer. It is safe to re-run. |
| `pip install` fails | Check your internet connection or proxy. Behind a corporate proxy, set `HTTPS_PROXY`. Delete `backend\.venv` and re-run. |
| `npm ci` fails | Delete `frontend\node_modules` and re-run. Use Node 20 or 22. |
| Red dot in the sidebar / "Cannot connect to Ollama" | Start the Ollama app (tray icon) or run `ollama serve`. Check `http://127.0.0.1:11434/api/version`. |
| "Model 'x' is not installed. Run: ollama pull x" | Run that command, or press **Pull** in Settings. |
| "does not support tool calling" (agent) | Choose a tool-capable coding model, such as `qwen2.5-coder`, `qwen3` or `llama3.1`. |
| Very slow answers | Use the `light` profile or smaller models, reduce `num_ctx`, close other RAM-heavy apps, or use a GPU. |
| Memory search shows "lexical fallback" | Pull the embedding model (`ollama pull nomic-embed-text`), then press **Re-embed** on the Memory page. |
| Port 8765 in use | Run `start.bat -Port 8770`. |
| "Unauthorized" | Auth is enabled. Under Settings → Security, click **Reveal (local only)** on the PC running KYRO, then **Use token**. |
| Import says "unrecognised JSON structure" | Upload the original export ZIP, or pick the correct type (for example "ChatGPT memory list"). |
| Scanned PDF has no text | Install Tesseract OCR, or upload page screenshots for the vision model. |
| Agent shows "Not verified" | No build or test ran after its last edit. Ask it to run the build/tests, or run them in the Terminal tab. |

## Resetting

- Settings only: delete `%USERPROFILE%\.kyro-ai\kyro.config.json`. Defaults are restored on the next start.
- Everything: stop KYRO and delete `%USERPROFILE%\.kyro-ai`. Your conversations and memories are deleted too, so
  export them first from the Memory page and the conversation menu.

## Backups

All your data is in `%USERPROFILE%\.kyro-ai`. To back up, copy that folder while KYRO is stopped. To keep
portable copies, use **Memory → Export JSON** and per-conversation Markdown/JSON export. A JSON memory export can
be re-imported on the Import page.
