# ============================================================================
#  common.ps1 - VVFile 开发脚本公共函数（不含副作用，可被 dot-source）
# ============================================================================
$ErrorActionPreference = 'Stop'

function Get-MuMuPaths {
    $config = Join-Path $env:APPDATA 'Netease\MuMuPlayer\install_config.json'
    $root = $null
    if (Test-Path $config) {
        try {
            $root = (Get-Content $config -Raw -Encoding UTF8 | ConvertFrom-Json).product.install_dir
        } catch { }
    }
    if ([string]::IsNullOrWhiteSpace($root)) { $root = 'C:\Program Files\Netease\MuMu' }

    $nx = Join-Path $root 'nx_main'
    $adb = Join-Path $nx 'adb.exe'
    if (-not (Test-Path $adb)) { throw "找不到 adb.exe：$adb（检查 MuMu 安装路径）" }

    [pscustomobject]@{
        Root    = $root
        Adb     = $adb
        Manager = (Join-Path $nx 'MuMuManager.exe')
    }
}

function Connect-MuMu {
    param([int]$VmIndex = 0, [int]$TimeoutSec = 60)

    $paths = Get-MuMuPaths
    $port = 16384
    if (Test-Path $paths.Manager) {
        try {
            $info = (& $paths.Manager info -v $VmIndex | ConvertFrom-Json)
            if ($info.adb_port) { $port = [int]$info.adb_port }
        } catch { }
    }

    $device = "127.0.0.1:$port"
    & $paths.Adb connect $device | Out-Null

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $line = (& $paths.Adb devices) | Where-Object { $_ -match [regex]::Escape($device) }
        if ($line -match '\sdevice\s*$') { break }
        Start-Sleep -Seconds 2
    }
    if (-not ($line -match '\sdevice\s*$')) {
        throw "设备 $device 未就绪，请确认 MuMu 已启动（adb devices 查看状态）"
    }

    [pscustomobject]@{ Adb = $paths.Adb; Device = $device }
}
