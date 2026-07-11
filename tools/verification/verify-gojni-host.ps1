[CmdletBinding()]
param(
    [string]$ToolchainConfig = (Join-Path $PSScriptRoot '..\..\app\src\main\go\toolchain.properties')
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ConfigPath = (Resolve-Path $ToolchainConfig).Path
$Config = @{}
foreach ($Line in Get-Content -LiteralPath $ConfigPath -Encoding utf8) {
    if ($Line -match '^\s*([^#][^=]*)=(.*)$') {
        $Config[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}
foreach ($Key in @('GO_ROOT', 'BUILD_CACHE_ROOT')) {
    if (-not $Config[$Key]) { throw "Missing toolchain property: $Key" }
}

$Go = Join-Path $Config.GO_ROOT 'bin\go.exe'
if (-not (Test-Path -LiteralPath $Go)) { throw "Go executable not found: $Go" }
$CacheRoot = Join-Path $Config.BUILD_CACHE_ROOT 'verification-host'
New-Item -ItemType Directory -Path "$CacheRoot\gopath\pkg\mod", "$CacheRoot\go-build", "$CacheRoot\tmp" -Force | Out-Null

$env:GOPATH = "$CacheRoot\gopath"
$env:GOMODCACHE = "$CacheRoot\gopath\pkg\mod"
$env:GOCACHE = "$CacheRoot\go-build"
$env:GOTMPDIR = "$CacheRoot\tmp"
$env:TEMP = $env:GOTMPDIR
$env:TMP = $env:GOTMPDIR
$env:GOENV = 'off'
$env:GOTELEMETRY = 'off'
$env:GOTOOLCHAIN = 'local'
$env:GOFLAGS = '-trimpath'
$env:AT2HT_RESIZER_TEST_INPUT = Join-Path $PSScriptRoot 'golden\android_jpeg_q100.jpg'
$env:AT2HT_RESIZER_TEST_GOLDEN = Join-Path $PSScriptRoot 'golden\original_libgojni_output.jpg'

Push-Location (Join-Path $ProjectRoot 'app\src\main\go')
try {
    & $Go test -v ./resizer
    if ($LASTEXITCODE -ne 0) { throw 'Go JPEG golden test failed.' }
} finally {
    Pop-Location
}
