#Requires -Version 5.1
<#
.SYNOPSIS
    连接 MuMu 模拟器 ADB（端口从 MuMuManager 读取，不写死）。
.EXAMPLE
    .\adb-connect.ps1
.EXAMPLE
    .\adb-connect.ps1 -Root
#>
[CmdletBinding()]
param(
    [int]$VmIndex = 0,
    [switch]$Root
)

. (Join-Path $PSScriptRoot 'common.ps1')

$ctx = Connect-MuMu -VmIndex $VmIndex
Write-Host ""
Write-Host "  [ok] 已连接 $($ctx.Device)" -ForegroundColor Green

if ($Root) {
    $uid = (& $ctx.Adb -s $ctx.Device shell su -c id -u 2>$null | Out-String).Trim()
    if ($uid -ne '0') {
        & $ctx.Adb -s $ctx.Device root 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        & $ctx.Adb connect $ctx.Device | Out-Null
        $uid = (& $ctx.Adb -s $ctx.Device shell id -u 2>$null | Out-String).Trim()
    }
    if ($uid -eq '0') {
        Write-Host "  [ok] root 已就绪 (uid=0)" -ForegroundColor Green
    } else {
        Write-Host "  [!] 未获得 root，请在 MuMu 设置中开启 ROOT 权限" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "  adb -s $($ctx.Device) shell" -ForegroundColor DarkGray
Write-Output $ctx.Device
