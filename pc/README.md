# VVFile 电脑端接收器

手机把文件/文件夹发到电脑时，电脑上跑的就是这个程序。托盘常驻，不占任务栏，开机自启，收到文件弹 Windows 通知。

## 一句话上手

1. 双击 `pc\dist\VvFileReceiver.exe`（或先看下面「编译」自己生成）。
2. 首次启动 Windows 会问防火墙，选「允许」。
3. 手机和电脑连同一个 WiFi / 热点，手机 VVFile 里长按文件（或文件夹，可多选）→ **发送到电脑**。
4. 手机自动找到这台电脑，点「发送」即可；文件落在接收目录里，文件夹保持原来的层级。

不需要在手机上填 IP，也没有口令。

## 反过来：电脑 → 手机

托盘图标右键 → **发送到手机…**，或者直接跑：

```
VvFileReceiver.exe --send [文件或文件夹...]
```

窗口里可以：

- **拖文件或文件夹进去**（一次拖多个也行，文件夹和文件混着拖也认）
- **添加文件 / 添加文件夹** 按钮（都可多选）
- 列表里 **多选 → 移除所选**，或 **清空待发送** 一把清掉
- 点 **刷新设备**：列出局域网里所有在应答的手机/平板（右上角下拉框，显示型号和 IP:端口），选一台再点 **发送到手机**；文件落到那台设备的 `Download/VVFile`，文件夹结构原样保留

手机侧要求：VVFile 打开过一次，通知栏有「正在接收电脑发来的文件」（前台服务，端口 TCP/UDP 7718）。
不想收时点通知里的「停止接收」，端口随之释放。


## 功能

- **自动发现**：手机往 UDP 7717 广播一句 `VVFILE?DISCOVER=1`，本程序回应自己的端口和接收目录；手机取回包的源 IP 连接，多网卡也不会指错路。
- **保持目录结构**：手机发文件夹时逐个文件带相对路径过来（`照片/2024/a.jpg`），空文件夹也会单独建，落盘结构与手机一致。
- **重名不覆盖**：`a.apk` 已存在就存成 `a (1).apk`，以此类推。
- **通知合并**：一批文件只弹一两条，不按文件数刷屏——最后一个文件静默 1.2 秒才弹（期间来的继续攒，最多攒 3.2 秒），弹完 5 秒冷却；文字是「收到 N 个文件 / 共多大 / 最后一个是谁」。单个文件照旧一条。
- **点通知直接定位**：通知横幅上点一下就会打开接收目录并**选中刚收到的那个文件**（资源管理器 `/select`）。文件多的时候不用自己翻。
- **托盘菜单**：打开接收文件夹 / 复制本机地址（备用）/ 手机端怎么用 / 打开日志 / 退出。
- **开机自启**：写 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 的 `VVFileReceiver` 值；`--uninstall` 可撤销。
- **单实例**：同一端口只跑一个，重复启动会安静退出。
- **图标**：exe、托盘、窗口标题栏都用手机端同一套图标（`pc/VvFileReceiver/app.ico`，紫底 + 白色 VV 文件夹，16–256 多尺寸，由 `res/mipmap-*/ic_launcher_foreground.png` + 启动图渐变合成）。
- **发送到手机**：主窗口（`--send` 或托盘菜单打开）支持拖放/多选，协议与手机端一致，只是方向反过来（`PUT /dir`、`PUT /upload`，端口 7718）。

## 命令行

```
VvFileReceiver.exe [--dir <接收目录>] [--port <端口>] [--uninstall] [--help]
VvFileReceiver.exe --send [文件或文件夹...]     # 直接打开发送到手机窗口
```

默认 `--dir E:\VVFile`、`--port 7717`。

## 编译

系统自带 .NET Framework 4.x 就够，不用装 SDK：

```
pwsh -NoProfile -ExecutionPolicy Bypass -File pc\VvFileReceiver\build.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File pc\VvFileReceiver\build.ps1 -Run      # 编完直接跑
pwsh -NoProfile -ExecutionPolicy Bypass -File pc\VvFileReceiver\build.ps1 -Stop     # 停掉正在跑的
pwsh -NoProfile -ExecutionPolicy Bypass -File pc\VvFileReceiver\build.ps1 -Uninstall
```

产物在 `pc\dist\VvFileReceiver.exe`（`dist\` 不入库）。

## 协议

一条请求一个文件，正文是原始字节，`Connection: close`。

| 请求 | 说明 |
| --- | --- |
| `GET /ping` | 探活，回 `{"ok":true,"app":"vvfile-receiver","version":1,"dir":"E:\\VVFile"}` |
| `PUT /upload` | 传文件。头 `X-VV-Path`（相对路径，UTF-8 百分号编码，`/` 分段）、`X-VV-Name`、`X-VV-Size`、`X-VV-Mtime`；正文为文件字节 |
| `PUT /dir` | 建目录。头 `X-VV-Path`，无正文 |
| UDP 7717 | 广播发现，收到含 `VVFILE` 的包就回 `{"ok":true,"app":"vvfile-receiver","port":7717,"dir":"...","host":"机器名"}` |

成功 `{"ok":true,"path":"E:\\VVFile\\...","bytes":123,"renamed":false}`；失败 `{"ok":false,"error":"..."}` 并给对应 HTTP 状态码（400 / 405 / 411 / 500）。

路径净化：逐段过滤 `..`、非法字符与尾部点，深度上限 24 段，段长上限 120 字符——手机传什么都越不出接收目录。

## 日志

`%LOCALAPPDATA%\VVFileReceiver\receiver.log`，超过 2 MB 自动滚动。托盘菜单「打开日志」直接看。
