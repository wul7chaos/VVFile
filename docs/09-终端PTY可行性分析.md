# Termux CLI 缝进内置终端的可行性分析

> 结论先行：**只搬 Termux 的「PTY + VT 仿真 + 终端视图」三件套（方案 A）可行，成本可控，许可干净；
> 想连 bash / apt / python 整套用户空间一起搬（方案 B），在当前 targetSdk 36 的架构下不可行**，除非把
> targetSdk 降到 28 并接受包体与 GPL 边界的变化。
>
> 本文只做分析，未改动任何代码。分析基于仓库根目录 `termux-app/`（clone 于 2026-10-25，
> HEAD `8629e632`）。

---

## 1. 现状与差距

| 能力 | 现在（VVFile 自研） | Termux 栈 |
| --- | --- | --- |
| 进程通道 | `ProcessBuilder("sh"/"su")` + 三路管道 | `fork` + `/dev/ptmx` 伪终端（PTY） |
| 输出模型 | 按行解析 ANSI，转成 `TerminalLine` 列表 | 维护整块屏幕缓冲（字符矩阵 + 属性），支持光标移动/清屏/滚动区 |
| 清屏 `clear` | **不生效**（CSI `2J` 被解析器丢弃，只能走菜单「清空」） | 原生生效 |
| `cd` 提示符 | 每条命令后追加 `printf "$PWD"` 哨兵回传 | shell 自身 `PS1` 输出，天然正确 |
| 交互式程序 | 不可用（无 TTY） | 可用：`vi`/`top`/`less`/`nano`（取决于系统自带 toybox 有什么） |
| Ctrl+C / Tab 补全 | 不可用（管道里发 `\x03` 无效） | 可用（PTY 会把信号发给前台进程组 / shell 补全） |
| 窗口尺寸变化 | 不涉及 | `TIOCSWINSZ`，`top`/`vi` 自适应 |
| 选择复制 / 滚回 | 自己写的 `SelectionContainer` + 5000 行缓冲 | `TerminalView` 自带文本选择光标 + transcript 滚回 |
| 依赖 | 0 个原生库 | 1 个 JNI 库 `libtermux.so`（约 200 行 C） |

一句话：现在的终端是「逐行命令回显器」，Termux 栈是「真终端」。用户抱怨的 `cls`/`clear` 无效，正是这个
差距的直接表现。

---

## 2. Termux 终端栈拆解（clone 实测）

| 模块 | 代码量 | 许可 | 说明 |
| --- | --- | --- | --- |
| `terminal-emulator/` | 6905 行 Java + `jni/termux.c` | **Apache-2.0**（仓库 `LICENSE.md` 明确把 terminal-view / terminal-emulator 列为例外，源自 jackpal 的 AOSP 终端） | PTY 会话、VT100/xterm 仿真、屏幕缓冲、SGR 颜色、滚回 |
| `terminal-view/` | 2430 行 Java | **Apache-2.0** | `TerminalView`（自定义 View，Canvas 绘制）、文本选择、`TerminalViewClient` |
| `app/`、`termux-shared/` | 数万行 | **GPLv3** | TermuxService、bootstrap 安装器、包管理、设置界面等 —— **不要碰**，会传染 |

关键接口（缝合时要实现的部分）：

- `JNI.createSubprocess(cmd, cwd, args, env, pidOut, rows, cols, cellW, cellH)` → 返回 PTY master fd；
  另有 `setPtyWindowSize` / `waitFor` / `close`。C 侧只是 `open("/dev/ptmx")` + `fork` + `setsid` + `execve`，
  没有 Termux 私有依赖。
- `TerminalSession(shellPath, cwd, args, env, transcriptRows, client)`：构造后调 `initializeEmulator(cols, rows, cellW, cellH)` 才真正 `execve`。
  `write(byte[])` 写输入，`resize()` 改窗口，`finishIfRunning()` 结束。
- `TerminalSessionClient`：**18 个方法**（`onTextChanged`/`onTitleChanged`/`onSessionFinished`/剪贴板/响铃/光标/7 个 log 方法……），
  大部分是空实现或 `Log.d`。
- `TerminalView` + `TerminalViewClient`：**约 25 个方法**（按键、`onCodePoint`、Ctrl/Alt 状态、长按、缩放、7 个 log……），
  `TerminalView` 需要 `attachSession(session)` 和 `setTerminalViewClient()`，输入走 `dispatchKeyEvent` / `inputCodePoint`。

**依赖干净**：`terminal-emulator` 与 `terminal-view` 之间只互相引用，**没有**引用 `com.termux.shared` / `com.termux.app`。
所以可以整目录拷进 VVFile，不需要引入 GPLv3 的 `termux-shared`。

构建方面：`terminal-emulator` 用 `ndkBuild` + `Android.mk`（`LOCAL_MODULE := libtermux`，源码就 `termux.c` 一个文件），
`ndkVersion 29.0.14206865`、`minSdk 21`、`compileSdk 36`。本机 SDK 已装同版本 NDK，可直接编译。

---

## 3. 方案 A：只搬终端栈（推荐）

### 3.1 做什么

1. 把 `terminal-emulator/`、`terminal-view/` 作为两个 module（或源码内嵌到 `:app`）并入，保留 Apache-2.0 的
   `NOTICE`，必要时重命名包名避免与将来依赖冲突。
2. 用它们替换 `TerminalSession`：`/system/bin/sh` 作为 shell，`cwd=/storage/emulated/0`，
   `env` 最少给 `TERM=xterm-256color`、`HOME`、`PATH=/system/bin:/system/xbin`、`LANG`。
3. `TerminalScreen` 里用 `AndroidView { TerminalView }` 承载；我们的 MT 风格顶栏、更多菜单、两排功能键全部保留。
4. 两排键改为真按键：Esc/Tab/方向/Home/End/PgUp/PgDn 走 `dispatchKeyEvent`，Ctrl/Alt 变成**真正可用**的
   修饰键（Termux 的 `TerminalViewClient.readControlKey()` 就是干这个的），↻ 改为重建 session。
5. `su` / `sudo` 保留：PTY 里 `su` 是标准交互（KernelSU 会弹授权），`sudo` 用一个 `ENV` 脚本注入
   shell 函数 `sudo() { su -c "$*"; }`；`PS1=':\w \$ '` 也在这个 `ENV` 里设，MT 的提示符自动成立。
6. 删掉我们自己的按行缓冲、哨兵、`clear` 拦截、`AnsiParser` 的显示路径（`AnsiParser` 的单测可以保留或随代码删除）。

### 3.2 为什么能成（没有踩到系统限制）

PTY 只是内核设施，`execve` 的目标是 **`/system/bin/sh`**（系统分区、只读、系统签名），
不受 Android 10 起对「应用私有目录内可写文件执行」的限制。方案 A 不落地任何自带二进制，
所以 **targetSdk 保持 36 也不用降级**。

### 3.3 落地后的体验变化

| 场景 | 结果 |
| --- | --- |
| `clear` / `cls` | 正常清屏（真转义序列） |
| `vi /data/local/tmp/a.txt` | 可编辑（toybox vi 存在时） |
| `top` | 正常刷新，随窗口大小自适应 |
| `ping` + Ctrl+C | 能中断（PTY 前台进程组） |
| `ls` 带颜色 | 正常（`TERM` + `LS_COLORS`） |
| 长输出 `cat` | 直接进屏幕缓冲，不再有「一行一条 TerminalLine」的 5000 行上限开销 |
| 长按选择复制 | `TerminalView` 自带选择手柄 |
| su | 真交互式 root shell，提示符由 shell 自己给 |

### 3.4 工作量与风险

| 项 | 估计 | 风险 |
| --- | --- | --- |
| 两个 module 并入 + CMake/ndkBuild 接线 + 许可声明 | 1–2 人日 | 低（NDK 版本已就绪） |
| 两个 Client 接口适配（43 个方法，多数是日志） | 0.5–1 人日 | 低 |
| Compose 宿主（AndroidView、尺寸测量、`resize`、IME 弹出/收回、返回键） | 1–2 人日 | **中**：AndroidView + IME 是传统坑，需要真机验证（尤其预测性返回和手势导航） |
| 功能键栏改真按键（Ctrl/Alt 状态机） | 0.5 人日 | 低 |
| ENV 脚本（PS1 / sudo 函数 / PATH）与 su 交互 | 0.5 人日 | 低 |
| 回归：切换 root、多窗口、后台回收、深色/浅色 | 1 人日 | 中 |
| **合计** | **约 4–6 人日** | 包体增量约 0.3 MB/ABI + 9k 行 Java |

构建注意：targetSdk 36 要求 arm64/x86_64 原生库 **16 KB 页对齐**；NDK 29 默认已按 16 KB 对齐，
但仍要用 `zipalign -c -P 16` 复核，并在 `packaging { jniLibs { useLegacyPackaging = false } }` 下保持 `.so` 未压缩。

---

## 4. 方案 B：连 Termux 用户空间（bash、apt、python）一起搬

### 4.1 直接阻塞点

1. **W^X（最关键）**：Android 10（API 29）起，`targetSdk ≥ 29` 的应用**不能 execve 自己数据目录里的可写文件**。
   Termux 之所以还能跑 `$PREFIX/bin/bash`，是因为它把 `targetSdkVersion` 钉在 **28**（`termux-app/gradle.properties` 实测为 28）。
   VVFile 现在是 **36**。
2. 把 targetSdk 降到 28 的代价（对 VVFile 尤其大）：
   - 存储权限回到 legacy 模型，`MANAGE_EXTERNAL_STORAGE` 语义、分区存储适配、`requestLegacyExternalStorage` 行为全变；
   - 前台服务、通知、后台限制回到旧行为，媒体播放服务（media3）和包变化广播都要重新适配；
   - targetSdk 28 在 Android 15/16 上会被系统按旧兼容模式跑，长期维护风险高（Google 每年提高安装门槛，28 已被 Play 拒收）。
3. 即便降了 targetSdk，还要搬 **bootstrap**（`bootstrap-aarch64.zip`，解包 100 MB+）：`$PREFIX` 必须是
   `/data/data/<pkg>/files/usr`，而 Termux 的预编译包路径写死为 `/data/data/com.termux/files/usr`
   （动态链接器的 `interpreter`、`rpath`、`termux-exec` 的 shebang 重写都依赖它）。
   改包名就得自己重编整套 bootstrap，等于维护一个 Termux fork。
4. **GPLv3 传染面**：`TermuxService`、`TermuxInstaller`、`termux-shared` 都是 GPLv3。VVFile 若要闭源分发，
   这部分不能抄，只能自己重写（那就是重写一个包管理器），或者整个应用转 GPLv3。

### 4.2 有没有折中

- **把 bash/coreutils 当 `.so` 塞进 `jniLibs`**：`/data/app/.../lib/` 是只读可执行目录，能绕过 W^X。
  但一个 CLI 用户空间是几百个可执行文件 + 动态库 + `/usr/share` 数据，全塞 `jniLibs` 不现实；
  只塞 `bash + 少量 busybox` 尚可，但这就不是「Termux 用户空间」了，收益不大。
- **proot / proot-distro**：proot 自身也得先能 exec，同样卡在 W^X。

结论：方案 B 不是「缝合」而是「fork 一个 Termux」，不建议。

---

## 5. 建议路线

1. **先做方案 A**（PTY + VT 仿真 + 系统 sh），这一步就把 `clear` 无效、Ctrl+C 无效、`vim/top` 不可用全部解决，
   且不动 targetSdk、不引入 GPL。
2. 方案 A 落地后再评估是否需要「自带 busybox / bash」这种小规模增强（可用 `jniLibs` 方式，逐个加，不碰 bootstrap）。
3. 只有当产品明确需要 `apt install`、`python`、`git` 这类完整用户空间时，才讨论方案 B，
   并且要先接受「targetSdk ≤ 28 + 自己维护 bootstrap + 可能的 GPLv3」三件事。

### 方案 A 的验收口径（供将来实施时用）

- `echo $TERM` 为 `xterm-256color`；`clear`/`cls` 立即清屏
- `top` 能刷新且窗口跟随旋转/软键盘缩放
- `ping 127.0.0.1` 后 Ctrl+C 能中断
- `su` 进入 root 后 `id` 返回 uid=0，`exit` 回普通会话且历史仍在
- 长按可选中复制，滚动可回看（transcript 行数可配）
- 两排功能键的 Esc/Tab/方向/Home/End/PgUp/PgDn/Ctrl/Alt 全部真实生效
- 深色/浅色主题下配色可读；targetSdk 仍为 36；APK 增量 < 1 MB

---

## 6. 附：clone 内关键文件索引

- `termux-app/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java` —— PTY 会话
- `termux-app/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` —— VT 仿真
- `termux-app/terminal-emulator/src/main/java/com/termux/terminal/TerminalSessionClient.java` —— 需实现的回调（18 个）
- `termux-app/terminal-emulator/src/main/jni/termux.c` —— `open("/dev/ptmx")` + `fork` + `execve`
- `termux-app/terminal-emulator/src/main/jni/Android.mk` —— `LOCAL_MODULE := libtermux`
- `termux-app/terminal-view/src/main/java/com/termux/view/TerminalView.java` —— 终端视图
- `termux-app/terminal-view/src/main/java/com/termux/view/TerminalViewClient.java` —— 需实现的回调（约 25 个）
- `termux-app/gradle.properties` —— `targetSdkVersion=28`（方案 B 的由来）、`ndkVersion=29.0.14206865`
- `termux-app/LICENSE.md` —— terminal-view / terminal-emulator 的 Apache-2.0 例外说明
