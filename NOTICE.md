# 第三方代码与许可

VVFile 自身代码为原创实现（含 UI 布局、交互与文案），**没有**参考或复用 MT 管理器的
任何资源、代码或界面素材。下面只列出确有源码引进的部分。

## Termux terminal-emulator / terminal-view

- 上游：<https://github.com/termux/termux-app>
- 许可：**Apache License 2.0**。依据是上游 `LICENSE.md` 的 Exceptions 一节：

  > Terminal Emulator for Android code is used which is released under Apache 2.0 license.
  > Check `terminal-view` and `terminal-emulator` libraries.

  这两个库源自 [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)，
  上游仓库的 GPLv3 **只覆盖 `app/` 与 `termux-shared`**，不覆盖这两个库。
- 引进范围（原样移植，未改动逻辑）：
  - `terminal-emulator/src/main/java/com/termux/terminal/**` →
    `app/src/main/java/com/termux/terminal/`
  - `terminal-view/src/main/java/com/termux/view/**` →
    `app/src/main/java/com/termux/view/`
  - `terminal-emulator/src/main/jni/termux.c` → `app/src/main/cpp/termux.c`
  - `terminal-view/src/main/res/drawable/text_select_handle_*.xml` →
    `app/src/main/res/drawable/`
- 改动清单：
  1. 合并进 `:app` 单一模块（原为两个 library 模块），`R` 引用改为 `com.vvfile.app.R`；
  2. `Android.mk` 只保留 `libtermux.so`，ABI 收敛为 `arm64-v8a` / `x86_64`；
  3. 选择手柄的菜单文案本地化为中文（`res/values/strings.xml`）；
  4. **未**引进 `termux-shared`（GPLv3）、`app/`（GPLv3）、bootstrap 与包管理。
- 与 VVFile 交互的部分全部是新写的：`PtyShell`、`TerminalViewModel`、
  `VvTerminalViewClient`、`TerminalScreen`。

Apache-2.0 全文见 <https://www.apache.org/licenses/LICENSE-2.0>。
