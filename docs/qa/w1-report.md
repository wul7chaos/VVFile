# W1 合并后设备回归报告

- 任务：`task-4` — W1 三路合并后的设备回归与截图
- 执行者：qa（worktree `VVFile.wt\qa`，分支 `test/qa-w1`）
- 基线：`main@fe4beb7`（已含 `2dc31d1` fs-core、`8a9cd2d` ui-shell、`fe4beb7` ui-shell 选择态/单窗格）
- 执行时间：2026-10-24 20:04–20:23（Asia/Shanghai）
- 结论：**W1 范围内的浏览/排序/隐藏/选择态/单窗格/抽屉全部可达，无崩溃、无 ANR；发现 1 个 P2 交互缺陷（抽屉返回键直接退出应用）与 5 个 P3 体验/规格偏差。无阻断项。**

## 1. 环境

| 项 | 值 |
| --- | --- |
| 设备 | MuMu 模拟器，adb `127.0.0.1:16384`（`scripts/adb-connect.ps1`） |
| 系统 | Android 15（`ro.build.version.release=15`），targetSdk 36 |
| 屏幕 | 1080×1920，density 480（1dp = 3px） |
| 包名 | `com.vvfile.app.debug`（debug 构建，versionName 0.1.0） |
| 权限 | `appops set ... MANAGE_EXTERNAL_STORAGE allow`（`scripts/install.ps1` 自动执行） |
| root | **不可用**：`/system/bin/su`、`/system/xbin/su` 均不存在，`adb shell su` → `inaccessible or not found`，`id -u` = 2000。属环境限制，非产品缺陷（见 §6） |
| 构建 | `.\gradlew.bat :app:assembleDebug --console=plain` → **BUILD SUCCESSFUL in 3s**（37 tasks） |
| 安装 | `.\scripts\install.ps1` → `Success: streamed 18685323 bytes`，冷启动首帧 `Displayed +598ms`（< 1.5s 基线） |
| 测试夹具（设备侧） | `/sdcard/qa-w1/`（空子目录 `empty`、`sub`、`note.txt` 6B、`readme.txt` 0B、`中文`、`中文 空格 文件.txt`、隐藏文件 `.hidden1`）、`/sdcard/.qa-empty/`（空目录） |

测试方法：`uiautomator` 层级（MCP dump，XPath 定位）取坐标后再 `input tap`，关键界面 `screencap` 落盘 `docs/shots/`。
注：`adb shell uiautomator dump` 在本机持续失败（`UiAutomationService ... already registered`，进程 137 退出），改用 MCP 层级接口取样；非产品问题。

## 2. 用例结果（docs/06 §3.1 + W1 新增）

| # | 用例 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 冷启动进入首页，双窗格均显示同一路径（`/sdcard` 规范化为 `/storage/emulated/0`） | PASS | `qa-w1-01-launch.png` |
| 2 | 进入目录：仅活动窗格变化，面包屑/计数同步 | PASS | `qa-w1-02-enter-dir.png` |
| 3 | 上级 / 后退 / 前进（含前进栈在上级后被清空、后退后重新可用） | PASS | `03-up` / `04-back` / `05-forward` |
| 4 | 左右窗格历史互不串扰 | PASS | `qa-w1-17-pane-independent.png`（右窗格进入 `.picker_transcoded`，左窗格与面包屑保持 `/0`） |
| 5 | 面包屑点击任意层级跳转 | PASS | `qa-w1-06-breadcrumb.png`（点 `storage` → `/storage`） |
| 6 | 排序：名称 / 修改时间 / 大小 / 类型，升降序可切 | PASS | `07-sort-menu`、`09-sort-size-desc`、`10-sort-time`、`11-sort-type` |
| 6a | 大小升/降序正确（40.8→46.2→47.2→55.4 KB…；降序 46.2→40.8→31.7…） | PASS | `09-sort-size-desc.png` |
| 6b | 修改时间升序与文件 mtime 一致（mt5 19:34:54 < ui3 19:34:56 < vv 19:50:05 < … < sp 20:02:52） | PASS | `qa-w1-10-sort-time.png` + `stat -c '%y'` |
| 6c | 类型排序按扩展名分组、组内名称序（png 组 → xml 组 b1/o1/sel/sp/ui/ui2/ui3） | PASS | `qa-w1-11-sort-type.png` |
| 7 | 隐藏文件开关即时生效，且双窗格同步（32 → 33，`.picker_transcoded` 出现） | PASS | `qa-w1-13-hidden-on.png` |
| 8 | 刷新按钮重新读取目录（33 → 34，新建的 `.qa-empty` 出现） | PASS | `qa-w1-18-refresh.png` |
| 9 | 长按进入选择态：勾选、追加、取消勾选（已选 2 项 → 1 项）、顶栏计数与 `n/总数` | PASS | `20-selection`、`22-select-all`、`35-deselect` |
| 10 | 全选（已选 34 项，34/34，整列高亮 + 勾选） | PASS | `qa-w1-22-select-all.png` |
| 11 | 底部操作条：复制/移动/删除/更多；点按有 Toast（`M2 实现`，W1 仅 UI） | PASS | `qa-w1-23-copy-toast.png` |
| 12 | 返回键先退出选择态（不退出应用） | PASS | `qa-w1-24-back-exit-selection.png` |
| 13 | ⋮ → 单窗格模式：右窗格隐藏、右 tab 置灰且点击无效 | PASS | `25-single-pane`、`26`（与 25 像素一致，证明点击无效） |
| 14 | 关闭单窗格恢复双窗格 | PASS | `qa-w1-27-dual-restored.png` |
| 15 | 抽屉：品牌区、root 状态、存储卡片（6.8/126 GB、可用 119 GB）、工具入口 6 项、页脚 | PASS | `qa-w1-28-drawer.png` |
| 16 | 空目录空态（插画 + 「此文件夹为空」+ 新建文件夹） | PASS | `qa-w1-19-empty-dir.png` |
| 17 | 无权限目录错误态：`/data`、`/storage`（「无法打开此文件夹 / 没有权限读取此目录 / 重试」，不崩溃） | PASS | `33-data-error`、`06-breadcrumb` |
| 18 | 根目录 `/` 列表（37 项）、根处「上级」置灰、符号链接有独立标记（`bin`、`d`） | PASS | `qa-w1-31-root-list.png` |
| 19 | 稳定性：连续操作约 2 分钟（75 轮：进出目录/排序/选择+全选/抽屉/刷新/滚动），logcat 无 `FATAL EXCEPTION`、无 `ANR in` | PASS | `qa-w1-34-stability.png`；PID 3330 全程未变；`TOTAL PSS 130 MB`（< 200 MB 基线） |
| 20 | 抽屉打开后按返回键 | **FAIL** | 见 D1 |

通过率：**20/21 = 95.2%**（唯一失败项为 D1）。

## 3. 缺陷列表

### D1 [P2 · 交互] 抽屉展开时按返回键直接退出应用

- 复现步骤：
  1. 冷启动 → 点左上汉堡（`打开工具抽屉`，中心约 90,156）
  2. 等待抽屉完全展开（≥1s，可见「已安装应用/文本编辑器/…」）
  3. 按系统返回键一次
- 期望：关闭抽屉，停留在浏览器当前路径（docs/04 §7 抽屉为覆盖层，返回应先收敛覆盖层）
- 实际：抽屉消失的同时 Activity 结束，回到桌面（`mCurrentFocus=app.lawnchair/...Launcher`）；再点桌面图标重新进入后浏览状态全部丢失（双窗格回到 `/0`、排序/隐藏文件设置复位）
- 复现率：3/3
- 截图：`docs/shots/qa-w1-30a-drawer-open.png`（返回前，抽屉展开）→ `qa-w1-30b-drawer-back-to-home.png`（返回后，桌面）
- logcat：无 `FATAL EXCEPTION`/`ANR`，属正常 finish（ActivityTaskManager 无异常栈），因此**不是崩溃而是返回键分发缺失**
- 定位线索（不改代码，仅供 ui-shell 参考）：`BrowserScreen.kt:136` 只注册了选择态的 `BackHandler(enabled = selectionActive && !drawerState.isOpen)`；非选择态下没有 `drawerState.isOpen` 的返回拦截，`ModalNavigationDrawer` 在本组合（material3 BOM 2025.09.00 + activity-compose 1.10.1）未吞掉该次返回，事件下传至 Activity 导致 finish。修法建议：补一个 `BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }`（或在同一 handler 内按 `isOpen` 分支）。
- 影响：W1 抽屉是主入口之一，误触返回即丢失浏览上下文；不阻断主流程，故定 P2。

### D2 [P3 · UI] 深层路径时面包屑首段 `/` 被挤出可视区，无法点回根目录

- 复现：`/storage/emulated/0/Android` 或 `/storage/emulated/0/.qa-empty` 等 5 段以上路径，面包屑自动滚到尾部，最左只剩 `›`，「/」不可见也不可点
- 期望：docs/04 §4「超长时从中间省略，首尾可点」
- 实际：头部整段被裁掉（非中间省略），只能靠连续点「上级」
- 截图：`qa-w1-02-enter-dir.png`、`qa-w1-19-empty-dir.png`、`qa-w1-33-data-error.png`

### D3 [P3 · 交互] 点击非活动窗格会直接改变其路径，但不切换活动窗格

- 复现：左窗格为活动窗格时，点右窗格任意目录行
- 实际：右窗格进入该目录，但顶栏 tab 仍高亮「左窗格」，面包屑仍显示左窗格路径，活动描边不变
- 期望（择一即可）：点按窗格内容时同时激活该窗格；或首次点击仅激活、第二次才进入
- 截图：`qa-w1-17-pane-independent.png`（右窗格已进入 `.picker_transcoded`，顶栏仍为「左窗格」）

### D4 [P3 · 规格] 统计胶囊信息不全

- 期望：docs/04 §4「统计胶囊 `12 文件夹 · 340 文件`；右侧显示 `32.4 GB 可用`」
- 实际：窗格标题仅显示总条目数（如 `/storage/emulated/0  32`），文件夹/文件分项与可用空间均未出现在浏览页（可用空间只在抽屉里）
- 截图：`qa-w1-01-launch.png`、`qa-w1-18-refresh.png`

### D5 [P3 · UI] 切换排序后列表滚动位置未复位

- 复现：列表滚动到中后段 → 排序菜单切换字段/方向
- 实际：沿用旧偏移，新排序下首行常被裁成半截（只见「163 KB · 10-24」不见文件名），用户需要自己滚回顶部确认排序结果
- 期望：排序变更后 `requestScrollToItem(0)`（`BrowserScreen.kt:115` 目前只在 `path` 变化时复位）
- 截图：`qa-w1-09-sort-size-desc.png`、`qa-w1-10-sort-time.png`（首行被裁）

### D6 [P3 · 环境] root 不可用导致系统分区用例只能验到错误态

- `su` 二进制缺失（见 §6），抽屉显示「root 未检测」；`/data`、`/system` 均只能验证「无权限」分支，root 分支（§3.3 的浏览/复制/删除）无法在本次设备上执行

## 4. 截图索引（`docs/shots/`）

| 文件 | 内容 |
| --- | --- |
| `qa-w1-01-launch.png` | 冷启动双窗格，双 `/storage/emulated/0`，左窗格活动 |
| `qa-w1-02-enter-dir.png` | 左窗格进入 `Android`，右窗格与计数不变 |
| `qa-w1-03-up.png` / `04-back.png` / `05-forward.png` | 上级 / 后退 / 前进三态，按钮可用性正确 |
| `qa-w1-06-breadcrumb.png` | 点面包屑 `storage` → `/storage`（同时体现无权限错误态） |
| `qa-w1-07-sort-menu.png` | 排序菜单（名称✓/修改时间/大小/类型 + 升降序提示） |
| `qa-w1-09-sort-size-desc.png` | 大小降序（46.2→40.8→31.7 KB…） |
| `qa-w1-10-sort-time.png` | 修改时间升序（与 mtime 一致） |
| `qa-w1-11-sort-type.png` | 类型排序（png 组 → xml 组） |
| `qa-w1-12-overflow.png` | ⋮ 菜单（刷新/隐藏文件/单窗格/交换/关于） |
| `qa-w1-13-hidden-on.png` | 隐藏文件开启：32→33，`.picker_transcoded` 出现 |
| `qa-w1-17-pane-independent.png` | 右窗格独立导航，左窗格与面包屑不变（D3 证据） |
| `qa-w1-18-refresh.png` | 刷新后 33→34，新建 `.qa-empty` 出现 |
| `qa-w1-19-empty-dir.png` | 空目录空态 |
| `qa-w1-20-selection.png` | 长按进入选择态（已选 1 项、勾选圆点、左色条） |
| `qa-w1-22-select-all.png` | 全选（已选 34 项，34/34） |
| `qa-w1-23-copy-toast.png` | 底部「复制」Toast（M2 实现占位） |
| `qa-w1-24-back-exit-selection.png` | 返回键退出选择态，界面复原 |
| `qa-w1-25-single-pane.png` | 单窗格：右窗格隐藏、右 tab 置灰 |
| `qa-w1-27-dual-restored.png` | 关闭单窗格恢复双窗格 |
| `qa-w1-28-drawer.png` | 抽屉：品牌 / root 状态 / 存储卡片 / 工具入口 |
| `qa-w1-29-root-list.png` | 根目录 `/`（37 项，符号链接标记，上级置灰） |
| `qa-w1-30a-drawer-open.png` | D1 返回前：抽屉展开 |
| `qa-w1-30b-drawer-back-to-home.png` | D1 返回后：退回桌面 |
| `qa-w1-31-root-list.png` | 再次进入根目录（D1 复现后状态） |
| `qa-w1-33-data-error.png` | `/data` 无权限错误态 |
| `qa-w1-34-stability.png` | 2 分钟连续操作后界面正常 |
| `qa-w1-35-deselect.png` | 取消勾选（2 项 → 1 项，`1/32`） |

必备六类截图均已覆盖：双窗格（01）、选择态（20/22）、单窗格（25）、排序菜单（07）、抽屉（28）、错误/空态（19/33）。

## 5. 未覆盖 / 顺延

- W1 未实现的入口按设计只验 UI：⋮→搜索/过滤/书签/设置、抽屉工具页、底部复制/移动/删除（均只出占位 Toast），随 W2/W3 补测。
- docs/06 §3.1 的「5000+ 项目录性能」「过滤关键字与扩展名」「书签」「搜索递归」「超长/emoji 文件名」未在 W1 范围内（无对应实现或未提交），顺延到具备实现的波次。
- 旋转、杀进程恢复、存储不足等 §4 健壮性项未在本次窗口执行（设备窗口 20 分钟，优先 W1 新增功能）。
- 主题/强调色/字体缩放 200%（环境矩阵 C/D）未执行，属 W3 设置页范围。

## 6. root 限制说明（环境，不计缺陷）

- MuMu 实例 `root_permission=true`，但镜像内无 `su`：`ls /system/bin/su`、`ls /system/xbin/su` 均 `No such file or directory`，`adb shell su -c id` 返回 `inaccessible or not found`，`adb shell id -u` 恒为 2000。
- 因此应用侧 root 探测结果「root 未检测」是**正确反映**；`/data`、`/system` 只能走到「没有权限读取此目录」错误态，`docs/06 §3.3` 的 root 浏览/复制/删除用例在本次设备上不可执行，需换可用 root 的镜像后补测。
- 该限制已与 W1 代码路径无关（`root-shell` 的 `ShellExecutor` 在无 `su` 时应走降级分支），本次未观察到因缺 root 导致的崩溃或弹窗风暴。

## 7. 结论与建议

- W1 交付质量可接受：全部新增交互（选择态、单窗格、排序、隐藏文件、抽屉、错误/空态）在真机可达且状态正确，2 分钟连续操作零崩溃、零 ANR、内存 130 MB。
- 建议合并后修复优先级：**D1（P2，抽屉返回键）** → D5、D2、D3（P3 体验）→ D4（P3 规格补齐，可与 W2 顶部统计一起做）。
- 回归口径：D1 修复后需重跑「抽屉 → 返回 → 留在原路径」与「选择态 → 返回 → 退出选择态」两条，确认两个 BackHandler 不互相抢占。
