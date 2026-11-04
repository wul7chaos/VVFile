# VVFile 接收器的编译脚本（用系统自带 csc.exe，不需要装 .NET SDK）
#
#   pwsh -File build.ps1                 编译到 pc\dist\VvFileReceiver.exe
#   pwsh -File build.ps1 -Run            编译后立刻启动
#   pwsh -File build.ps1 -Stop           结束正在运行的接收器
#   pwsh -File build.ps1 -Uninstall      取消开机启动
param(
    [switch]$Run,
    [switch]$Stop,
    [switch]$Uninstall,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $scriptDir) { $scriptDir = Split-Path -Parent $PSCommandPath }

if (-not $OutDir) { $OutDir = Join-Path (Split-Path -Parent $scriptDir) 'dist' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$exe = Join-Path $OutDir 'VvFileReceiver.exe'

if ($Stop) {
    $procs = Get-Process VvFileReceiver -ErrorAction SilentlyContinue
    if (-not $procs) { Write-Host '接收器没在跑' }
    foreach ($proc in $procs) {
        Write-Host "结束进程 $($proc.Id)"
        $proc.Kill()
    }
    return
}

if ($Uninstall) {
    if (Test-Path $exe) { & $exe --uninstall }
    Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' `
        -Name VvFileReceiver -ErrorAction SilentlyContinue
    Write-Host '已取消开机启动'
    return
}

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) {
    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
}
if (-not (Test-Path $csc)) { throw '找不到 csc.exe（需要 .NET Framework 4.x）' }

Write-Host "编译 -> $exe"
$sources = Join-Path $scriptDir 'VvFileReceiver.cs'
& $csc /nologo /target:winexe /platform:anycpu /optimize+ /out:"$exe" `
    /r:System.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll $sources
if ($LASTEXITCODE -ne 0) { throw "编译失败（exit $LASTEXITCODE）" }

Write-Host "完成：$exe"
if ($Run) {
    Start-Process -FilePath $exe
    Write-Host '已启动（托盘图标，右键有菜单）'
}
