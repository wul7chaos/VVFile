#Requires -Version 5.1
<#
.SYNOPSIS
    截取 MuMu 当前画面到 docs/shots/。
.EXAMPLE
    .\shot.ps1 -Name m0-browser
#>
[CmdletBinding()]
param(
    [string]$Name = 'shot',
    [int]$VmIndex = 0
)

. (Join-Path $PSScriptRoot 'common.ps1')

$ctx = Connect-MuMu -VmIndex $VmIndex
$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $root 'docs\shots'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$file = Join-Path $dir ("{0}-{1}.png" -f $Name, (Get-Date -Format 'yyyyMMdd-HHmmss'))

$tmp = '/sdcard/__vvfile_shot.png'
& $ctx.Adb -s $ctx.Device shell screencap -p $tmp | Out-Null
& $ctx.Adb -s $ctx.Device pull $tmp $file | Out-Null
& $ctx.Adb -s $ctx.Device shell rm -f $tmp | Out-Null

if (Test-Path $file) {
    Write-Host "  [ok] $file" -ForegroundColor Green
} else {
    throw "截图失败"
}
