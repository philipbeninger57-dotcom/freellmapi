# Run all automated tests. Integration tests against real models run automatically when
# 'qwen2.5:1.5b' and 'all-minilm' are installed in Ollama (ollama pull qwen2.5:1.5b; ollama pull all-minilm).
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$vpy = Join-Path $Root "backend\.venv\Scripts\python.exe"
Set-Location (Join-Path $Root "backend")
& $vpy -m pip install -q -r requirements-dev.txt
& $vpy -m pytest -q -rs
$py = $LASTEXITCODE
Set-Location (Join-Path $Root "frontend")
npx tsc -b; $tsc = $LASTEXITCODE
npx vitest run; $vt = $LASTEXITCODE
Write-Host "`npytest exit=$py  tsc exit=$tsc  vitest exit=$vt"
if ($py -or $tsc -or $vt) { exit 1 }
