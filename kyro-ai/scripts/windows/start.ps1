<#
.SYNOPSIS  Start KYRO AI (ensures Ollama is running, then launches the backend and opens the browser).
#>
param([int]$Port = 0, [switch]$NoBrowser)
$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$vpy = Join-Path $Root "backend\.venv\Scripts\python.exe"
if (-not (Test-Path $vpy)) {
  Write-Host "KYRO AI is not installed yet. Run install.bat first." -ForegroundColor Red
  Read-Host "Press Enter to exit"; exit 1
}
if (-not (Test-Path (Join-Path $Root "frontend\dist\index.html"))) {
  Write-Host "Frontend build missing. Run: cd frontend; npm run build  (or re-run install.bat)" -ForegroundColor Yellow
}

function Ollama-Up { try { Invoke-RestMethod "http://127.0.0.1:11434/api/version" -TimeoutSec 2 | Out-Null; return $true } catch { return $false } }
if (-not (Ollama-Up)) {
  $app = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama app.exe"
  if (Test-Path $app) { Start-Process $app } elseif (Get-Command ollama -ErrorAction SilentlyContinue) { Start-Process -WindowStyle Hidden ollama -ArgumentList "serve" }
  Write-Host "Waiting for Ollama..."
  for ($i = 0; $i -lt 30 -and -not (Ollama-Up); $i++) { Start-Sleep 1 }
  if (-not (Ollama-Up)) { Write-Host "Ollama is not running. KYRO will start, but chats need Ollama (install from ollama.com)." -ForegroundColor Yellow }
}

$kyroArgs = @("-m", "kyro")
if ($Port -gt 0) { $kyroArgs += @("--port", "$Port") }
if (-not $NoBrowser) { $kyroArgs += "--open" }
Set-Location (Join-Path $Root "backend")
& $vpy @kyroArgs
if ($LASTEXITCODE -ne 0) {
  Write-Host "`nKYRO stopped with exit code $LASTEXITCODE. If the port is in use, run: start.bat -Port 8770" -ForegroundColor Red
  Read-Host "Press Enter to exit"
}
