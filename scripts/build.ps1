#Requires -Version 5.1
<#
.SYNOPSIS
    构建 VVFile。
.EXAMPLE
    .\build.ps1
.EXAMPLE
    .\build.ps1 -Variant release -Clean
#>
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug',
    [switch]$Clean
)

$root = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradlew)) { throw "缺少 gradlew.bat，请先生成 Gradle wrapper" }

Push-Location $root
try {
    if ($Clean) { & $gradlew clean }
    $task = if ($Variant -eq 'release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
    & $gradlew $task --stacktrace
    if ($LASTEXITCODE -ne 0) { throw "构建失败（exit $LASTEXITCODE）" }

    $apk = if ($Variant -eq 'release') {
        Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
    } else {
        Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    }
    if (Test-Path $apk) {
        $size = [math]::Round((Get-Item $apk).Length / 1MB, 2)
        Write-Host ""
        Write-Host "  [ok] $apk ($size MB)" -ForegroundColor Green
    }
} finally {
    Pop-Location
}
