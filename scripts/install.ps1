#Requires -Version 5.1
<#
.SYNOPSIS
    安装并启动 VVFile 到 MuMu（自动授予所有文件访问权限）。
.EXAMPLE
    .\install.ps1
.EXAMPLE
    .\install.ps1 -Release
#>
[CmdletBinding()]
param(
    [int]$VmIndex = 0,
    [switch]$Release
)

. (Join-Path $PSScriptRoot 'common.ps1')

$root = Split-Path -Parent $PSScriptRoot
$apk = if ($Release) {
    Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
} else {
    Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
}
$package = if ($Release) { 'com.vvfile.app' } else { 'com.vvfile.app.debug' }

if (-not (Test-Path $apk)) { throw "未找到 $apk，请先运行 .\build.ps1" }

$ctx = Connect-MuMu -VmIndex $VmIndex
Write-Host "  [..] 安装 $apk" -ForegroundColor Cyan
& $ctx.Adb -s $ctx.Device install -r -t $apk
if ($LASTEXITCODE -ne 0) { throw "安装失败（exit $LASTEXITCODE）" }

& $ctx.Adb -s $ctx.Device shell appops set $package MANAGE_EXTERNAL_STORAGE allow 2>$null | Out-Null
& $ctx.Adb -s $ctx.Device shell am start -n "$package/com.vvfile.app.MainActivity" | Out-Null
Write-Host "  [ok] 已启动 $package" -ForegroundColor Green
Write-Host "  [i] 如顶部仍有权限横幅，点「去授权」手动开启一次" -ForegroundColor DarkGray
