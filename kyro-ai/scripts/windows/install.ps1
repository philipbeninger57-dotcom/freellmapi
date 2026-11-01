<#
.SYNOPSIS
  KYRO AI installer for Windows 10/11.

.DESCRIPTION
  Checks and (optionally) installs Python 3.11+, Node.js 20+, Ollama, creates the Python virtual
  environment, installs backend + frontend dependencies, builds the UI, writes a default
  configuration sized for your RAM and downloads the local models.

.PARAMETER Models
  standard (default, 32 GB RAM: 14B models) | light (16 GB RAM: 4-8B models) | none (skip downloads)

.PARAMETER WithJava
  Also install Temurin JDK 21 and Maven (for Minecraft plugin builds by the coding agent).

.PARAMETER NoWinget
  Never install anything with winget; only report what is missing.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\windows\install.ps1 -Models standard -WithJava
#>
param(
  [ValidateSet("standard", "light", "none")] [string]$Models = "standard",
  [switch]$WithJava,
  [switch]$NoWinget
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Backend = Join-Path $Root "backend"
$Frontend = Join-Path $Root "frontend"
$DataDir = if ($env:KYRO_DATA_DIR) { $env:KYRO_DATA_DIR } else { Join-Path $env:USERPROFILE ".kyro-ai" }
$Log = Join-Path $Root "install.log"
Start-Transcript -Path $Log -Append | Out-Null

function Step($t) { Write-Host "`n==> $t" -ForegroundColor Cyan }
function Ok($t) { Write-Host "    [ok] $t" -ForegroundColor Green }
function Warn($t) { Write-Host "    [!] $t" -ForegroundColor Yellow }
function Fail($t) {
  Write-Host "`n[ERROR] $t" -ForegroundColor Red
  Write-Host "See $Log and docs\TROUBLESHOOTING.md. Fix the issue and re-run the installer (it is safe to re-run)." -ForegroundColor Red
  Stop-Transcript | Out-Null
  exit 1
}
function Refresh-Path {
  $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
function Have($cmd) { return [bool](Get-Command $cmd -ErrorAction SilentlyContinue) }
function Winget-Install($id, $name) {
  if ($NoWinget) { Fail "$name is missing. Install it manually (winget install $id) and re-run." }
  if (-not (Have winget)) { Fail "$name is missing and winget is not available. Install $name manually and re-run." }
  Write-Host "    installing $name via winget ($id)..."
  winget install --id $id -e --accept-source-agreements --accept-package-agreements --silent
  if ($LASTEXITCODE -ne 0) { Warn "winget returned $LASTEXITCODE for $name" }
  Refresh-Path
}

Write-Host "KYRO AI installer" -ForegroundColor Magenta
Write-Host "Install dir: $Root"
Write-Host "Data dir:    $DataDir"

# ---------------------------------------------------------------- system
Step "System check"
$os = Get-CimInstance Win32_OperatingSystem
$ramGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
Ok "$($os.Caption) (build $($os.BuildNumber)), $ramGB GB RAM"
if ([int]$os.BuildNumber -lt 17763) { Fail "Windows 10 1809 or newer is required." }
if ($Models -eq "standard" -and $ramGB -lt 24) { Warn "Less than 24 GB RAM detected; switching to the 'light' model profile."; $Models = "light" }
$gpu = (Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join ", "
Ok "GPU: $gpu"
$free = [math]::Round((Get-PSDrive -Name ($Root.Path.Substring(0,1))).Free / 1GB)
if ($free -lt 30 -and $Models -ne "none") { Warn "Only $free GB free disk space. The standard model set needs ~35 GB." }

# ---------------------------------------------------------------- python
Step "Python 3.11+"
$py = $null
foreach ($cand in @("py -3.12", "py -3.11", "py -3.13", "python")) {
  try {
    $v = & cmd /c "$cand -c ""import sys;print('%d.%d'%sys.version_info[:2])"" 2>nul"
    if ($v -and [version]$v -ge [version]"3.11") { $py = $cand; break }
  } catch {}
}
if (-not $py) { Winget-Install "Python.Python.3.12" "Python 3.12"; $py = "py -3.12" }
Ok "using '$py'"

# ---------------------------------------------------------------- node
Step "Node.js 20+"
if (-not (Have node)) { Winget-Install "OpenJS.NodeJS.LTS" "Node.js LTS" }
if (-not (Have node)) { Fail "Node.js still not found. Open a new terminal (PATH refresh) and re-run." }
$nodeMajor = [int]((node --version).TrimStart("v").Split(".")[0])
if ($nodeMajor -lt 20) { Fail "Node.js $(node --version) is too old; install Node 20 or newer." }
Ok "node $(node --version)"

# ---------------------------------------------------------------- ollama
Step "Ollama"
if (-not (Have ollama)) {
  $localOllama = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"
  if (Test-Path $localOllama) { $env:Path += ";" + (Split-Path $localOllama) }
  else { Winget-Install "Ollama.Ollama" "Ollama" }
}
if (-not (Have ollama)) { Fail "Ollama not found. Download it from https://ollama.com/download/windows and re-run." }
Ok "$(ollama --version)"
function Ollama-Up { try { Invoke-RestMethod "http://127.0.0.1:11434/api/version" -TimeoutSec 2 | Out-Null; return $true } catch { return $false } }
if (-not (Ollama-Up)) {
  Write-Host "    starting ollama serve..."
  Start-Process -WindowStyle Hidden -FilePath "ollama" -ArgumentList "serve"
  for ($i = 0; $i -lt 30 -and -not (Ollama-Up); $i++) { Start-Sleep 1 }
}
if (Ollama-Up) { Ok "Ollama server running on 127.0.0.1:11434" } else { Warn "Ollama server did not start; start the Ollama app manually." }

# ---------------------------------------------------------------- optional java
if ($WithJava) {
  Step "Java 21 + Maven (optional, for Minecraft plugin builds)"
  if (-not (Have java)) { Winget-Install "EclipseAdoptium.Temurin.21.JDK" "Temurin JDK 21" }
  if (-not (Have mvn)) { Winget-Install "Apache.Maven" "Apache Maven" }
  if (Have java) { Ok (cmd /c "java -version 2>&1" | Select-Object -First 1) }
  if (Have mvn) { Ok "maven available" } else { Warn "Maven not on PATH yet; open a new terminal later." }
}
if (-not (Have git)) { Warn "Git not found (optional, for agent git commands): winget install Git.Git" }

# ---------------------------------------------------------------- backend
Step "Backend (Python virtual environment)"
$venv = Join-Path $Backend ".venv"
if (-not (Test-Path (Join-Path $venv "Scripts\python.exe"))) {
  & cmd /c "$py -m venv ""$venv"""
  if ($LASTEXITCODE -ne 0) { Fail "Could not create the virtual environment." }
}
$vpy = Join-Path $venv "Scripts\python.exe"
& $vpy -m pip install --upgrade pip --quiet
& $vpy -m pip install -r (Join-Path $Backend "requirements.txt") --quiet
if ($LASTEXITCODE -ne 0) { Fail "pip install failed (check your internet connection / proxy)." }
& $vpy -c "import fastapi, uvicorn, httpx, numpy, pypdf; print('imports ok')"
if ($LASTEXITCODE -ne 0) { Fail "Backend dependencies are not importable." }
Ok "backend dependencies installed"

# ---------------------------------------------------------------- frontend
Step "Frontend (npm install + production build)"
Push-Location $Frontend
try {
  if (Test-Path "package-lock.json") { npm ci --no-audit --no-fund } else { npm install --no-audit --no-fund }
  if ($LASTEXITCODE -ne 0) { Fail "npm install failed." }
  npm run build
  if ($LASTEXITCODE -ne 0) { Fail "Frontend build failed." }
} finally { Pop-Location }
Ok "UI built to frontend\dist"

# ---------------------------------------------------------------- config
Step "Configuration"
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
$cfg = Join-Path $DataDir "kyro.config.json"
$profiles = @{
  standard = @{ chat = "qwen3:14b"; reasoning = "deepseek-r1:14b"; coding = "qwen2.5-coder:14b"; vision = "qwen2.5vl:7b"; embedding = "nomic-embed-text"; summarize = "qwen3:8b"; fast = "qwen3:4b" }
  light    = @{ chat = "qwen3:8b";  reasoning = "qwen3:8b";        coding = "qwen2.5-coder:7b";  vision = "qwen2.5vl:7b"; embedding = "nomic-embed-text"; summarize = "qwen3:4b"; fast = "qwen3:4b" }
}
$modelProfile = if ($Models -eq "none") { $profiles.standard } else { $profiles[$Models] }
if (-not (Test-Path $cfg)) {
  @{ models = $modelProfile; num_ctx = $(if ($Models -eq "light") { 8192 } else { 16384 }) } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $cfg
  Ok "wrote $cfg"
} else { Ok "keeping existing $cfg" }

# ---------------------------------------------------------------- models
if ($Models -ne "none") {
  Step "Downloading models ($Models profile). This can take a while."
  $list = $modelProfile.Values | Sort-Object -Unique
  foreach ($m in $list) {
    Write-Host "    ollama pull $m"
    ollama pull $m
    if ($LASTEXITCODE -ne 0) { Warn "Failed to pull $m - retry later with: ollama pull $m (or from Settings in KYRO)" }
  }
  Ok "models: $(($list) -join ', ')"
}

# ---------------------------------------------------------------- shortcut
Step "Desktop shortcut"
try {
  $ws = New-Object -ComObject WScript.Shell
  $lnk = $ws.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "KYRO AI.lnk"))
  $lnk.TargetPath = Join-Path $Root "start.bat"
  $lnk.WorkingDirectory = $Root
  $lnk.Save()
  Ok "created 'KYRO AI' on your desktop"
} catch { Warn "Could not create a shortcut: $_" }

Write-Host "`nKYRO AI is installed. Start it with start.bat (or the desktop shortcut)." -ForegroundColor Green
Write-Host "Then open http://127.0.0.1:8765" -ForegroundColor Green
Stop-Transcript | Out-Null
