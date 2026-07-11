[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$CaptureDirectory
)

$ErrorActionPreference = 'Stop'
$Golden = Join-Path $PSScriptRoot 'golden'
$ActualInput = Join-Path $CaptureDirectory 'android_jpeg_q100.jpg'
$ActualOutput = Join-Path $CaptureDirectory 'app_output.jpg'
$ExpectedInput = Join-Path $Golden 'android_jpeg_q100.jpg'
$ExpectedOutput = Join-Path $Golden 'original_libgojni_output.jpg'

foreach ($Path in @($ActualInput, $ActualOutput, $ExpectedInput, $ExpectedOutput)) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Missing comparison file: $Path" }
}

Get-FileHash -Algorithm SHA256 -LiteralPath $ExpectedInput, $ActualInput, $ExpectedOutput, $ActualOutput
& fc.exe /b $ExpectedInput $ActualInput
if ($LASTEXITCODE -ne 0) { throw 'Android JPEG quality-100 intermediate bytes differ.' }
& fc.exe /b $ExpectedOutput $ActualOutput
if ($LASTEXITCODE -ne 0) { throw 'Final JPEG bytes differ.' }
Write-Host 'BIT EXACT: Android intermediate JPEG and final JPEG both match the original library.'
