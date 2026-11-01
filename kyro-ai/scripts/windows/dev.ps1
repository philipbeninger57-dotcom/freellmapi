# Development mode: backend with auto-reload on :8765 and Vite dev server with hot reload on :5173.
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$vpy = Join-Path $Root "backend\.venv\Scripts\python.exe"
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$Root\backend'; & '$vpy' -m uvicorn kyro.main:factory --factory --reload --port 8765"
Set-Location (Join-Path $Root "frontend")
npm run dev
