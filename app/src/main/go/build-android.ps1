[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$ProjectRoot = (Resolve-Path "$PSScriptRoot\..\..\..\..").Path
$ToolchainConfig = Join-Path $PSScriptRoot 'toolchain.properties'
if (-not (Test-Path -LiteralPath $ToolchainConfig)) {
    throw 'Missing toolchain.properties. Copy toolchain.properties.example and edit its four paths.'
}
$Config = @{}
foreach ($Line in Get-Content -LiteralPath $ToolchainConfig -Encoding utf8) {
    if ($Line -match '^\s*([^#][^=]*)=(.*)$') {
        $Config[$Matches[1].Trim()] = $Matches[2].Trim()
    }
}
foreach ($Key in @('GO_ROOT', 'GO_TOOLS_ROOT', 'ANDROID_SDK_ROOT', 'BUILD_CACHE_ROOT')) {
    if (-not $Config[$Key]) { throw "Missing toolchain property: $Key" }
}
$GoRoot = $Config.GO_ROOT
$GoToolsRoot = $Config.GO_TOOLS_ROOT
$AndroidSdkRoot = $Config.ANDROID_SDK_ROOT
$BuildCacheRoot = $Config.BUILD_CACHE_ROOT

$BuildRoot = Join-Path $BuildCacheRoot 'libgojni'
$GoWorkspace = Join-Path $BuildRoot 'gopath'
$GoBuildCache = Join-Path $BuildRoot 'go-build-cache'
$TempRoot = Join-Path $BuildRoot 'tmp'
$AndroidUserHome = Join-Path $BuildRoot 'android-user-home'

foreach ($Directory in @($BuildRoot, $GoWorkspace, $GoBuildCache, $TempRoot, $AndroidUserHome)) {
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
}

# Keep every writable cache and temporary artifact below the configured cache
# root. PATH is changed only for this process because gomobile locates go by
# command name. System/user environment configuration is never modified.
$EnvironmentNames = @(
    'GOPATH', 'GOMODCACHE', 'GOCACHE', 'GOTELEMETRY', 'GOENV', 'GOTOOLCHAIN',
    'GOFLAGS', 'GOTMPDIR', 'TEMP', 'TMP', 'ANDROID_USER_HOME', 'ANDROID_HOME',
    'ANDROID_SDK_ROOT', 'ANDROID_NDK_HOME', 'CGO_LDFLAGS', 'PATH'
)
$OriginalEnvironment = @{}
foreach ($Name in $EnvironmentNames) {
    $OriginalEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
}
Push-Location $PSScriptRoot
try {
$env:GOPATH = $GoWorkspace
$env:GOMODCACHE = Join-Path $GoWorkspace 'pkg\mod'
$env:GOCACHE = $GoBuildCache
$env:GOTELEMETRY = 'off'
$env:GOENV = 'off'
$env:GOTOOLCHAIN = 'local'
$env:GOFLAGS = '-trimpath'
$env:GOTMPDIR = $TempRoot
$env:TEMP = $TempRoot
$env:TMP = $TempRoot
$env:ANDROID_USER_HOME = $AndroidUserHome
$Go = Join-Path $GoRoot 'bin\go.exe'
$Gomobile = Join-Path $GoToolsRoot 'bin\gomobile.exe'
if (-not (Test-Path -LiteralPath $Go)) { throw "Go executable not found: $Go" }
if (-not (Test-Path -LiteralPath $Gomobile)) { throw "gomobile executable not found: $Gomobile" }

$GoVersion = (& $Go version)
if ($LASTEXITCODE -ne 0) { throw 'Failed to execute go version.' }
if ($GoVersion -notmatch 'go1\.24(?:\.0)?\b') {
    throw "Go 1.24.0 is required for reproducible output; found: $GoVersion"
}

$SdkRoot = (Resolve-Path -LiteralPath $AndroidSdkRoot).Path

$NdkVersion = '28.2.13676358'
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_NDK_HOME = Join-Path $SdkRoot "ndk\$NdkVersion"
if (-not (Test-Path -LiteralPath $env:ANDROID_NDK_HOME)) {
    throw "Android NDK $NdkVersion is required. Install it with Android Studio SDK Manager."
}
$env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
$env:PATH = "$(Join-Path $GoRoot 'bin');$(Join-Path $GoToolsRoot 'bin');$env:PATH"

$AarPath = Join-Path $BuildRoot 'resizer.aar'
$ExtractPath = Join-Path $BuildRoot 'aar'
$Destination = Join-Path $ProjectRoot 'app\src\main\jniLibs\arm64-v8a\libgojni.so'

    & $Go mod download
    if ($LASTEXITCODE -ne 0) { throw 'go mod download failed.' }
    if (Test-Path -LiteralPath $AarPath) {
        Remove-Item -LiteralPath $AarPath -Force
    }
    & $Gomobile bind -target=android/arm64 -androidapi=27 -o $AarPath ./resizer
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $AarPath)) {
        throw 'gomobile bind failed; no AAR was generated.'
    }
    if (Test-Path -LiteralPath $ExtractPath) {
        Remove-Item -LiteralPath $ExtractPath -Recurse -Force
    }
    Expand-Archive -LiteralPath $AarPath -DestinationPath $ExtractPath
    Copy-Item -LiteralPath (Join-Path $ExtractPath 'jni\arm64-v8a\libgojni.so') -Destination $Destination -Force
    Write-Host "Updated $Destination"
} finally {
    foreach ($Name in $EnvironmentNames) {
        $OriginalValue = $OriginalEnvironment[$Name]
        if ($null -eq $OriginalValue) {
            Remove-Item -Path "Env:$Name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "Env:$Name" -Value $OriginalValue
        }
    }
    Pop-Location
}
