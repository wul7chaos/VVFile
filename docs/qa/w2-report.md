# W2 回归报告（文件操作 / 编辑器）

- 任务：`task-8` — W2 文件操作与文本编辑器设备回归
- 执行者：qa（worktree `VVFile.wt\qa`，分支 `test/qa-w2`，基于 `main@9260dbf`）
- 设备：MuMu `127.0.0.1:16384`，Android 15，1080×1920@480dpi，包 `com.vvfile.app.debug`（versionName 0.1.0）
- 构建：`.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL；`scripts/install.ps1` → Success；冷启动 `TotalTime 635ms`
- 执行窗口：2026-10-24 21:52–22:44
- 结论：**核心文件操作（复制/移动/冲突四分支/取消/删除/重命名/新建/ZIP 往返/Zip-Slip 防护）全部通过；编辑器存在 1 个 P2 会话复用缺陷，阻断 GBK 用例；分享与长时压测未覆盖。**

## 1. 测试夹具（设备侧）

```
/sdcard/qa-w2/
├── src/                  ← 左窗格
│   ├── aa.txt            UTF-8，21 B
│   ├── gbk.txt           GBK 编码（c4e3 bac3 …），11 B
│   ├── big.bin           2 MB（移动用例）
│   ├── big2.bin          1.2 GB（取消用例）
│   ├── nested/{y.md, deep/x.txt}
│   ├── nested.zip        291 B（nested 压缩产物）
│   ├── slip.zip          246 B（含 `../evil.txt` 的 Zip-Slip 样本，host 用 .NET ZipArchive 构造）
│   └── 中文 名.txt / 中文 名.zip / newfile.txt / newdir /
├── dst/                  ← 右窗格（预置 a.txt 制造同名冲突）
└── conflict/             备用空目录
```

## 2. 用例结果

| # | 用例 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 双窗格跨目录复制（左 src → 右 dst） | PASS | `qa-w2-09-selection`、`13-replace-done`（dst/a.txt 21 B，内容与源一致） |
| 2 | 进度对话框（标题/文件名/百分比/取消按钮） | PASS | `qa-w2-20-progress.png`（68%，big2.bin） |
| 3 | 完成 Toast + 目标窗格自动刷新 | PASS | `13-replace-done`（完成：成功 1 项）、`17-move-done` |
| 4 | 冲突：**取消** | PASS | `11-conflict-cancel`（对话框关闭，dst 内容未变，仍在选择态） |
| 5 | 冲突：**覆盖** | PASS | `13-replace-done`（dst/a.txt 由 OLD→`hello w2/second line`） |
| 6 | 冲突：**跳过** | PASS | `14-skip`（完成：成功 0 项，跳过 1 项；dst 保留 SKIP-MARK） |
| 7 | 冲突：**重命名** | PASS | `15-rename-branch`（生成 `a (1).txt`，原名保留） |
| 8 | 移动（同分区） | PASS | `16-move-selection`、`17-move-done`（src/b.bin 消失，dst/b.bin 2.0 MB） |
| 9 | 取消大文件复制 + 临时文件残留 | PASS | `20-progress`（68% 时取消）→ `21-cancelled`（已取消操作）；`find /sdcard -name '.vvfile_tmp_*'` 为空，dst 无半成品 |
| 10 | 删除二次确认 | PASS | `22-delete-confirm`（删除确认 / 确定删除已选的 1 项吗？此操作不可撤销） |
| 11 | 删除执行 | PASS | `23-delete-done`（中文 名.txt 从 src 消失，其余完好） |
| 12 | 重命名：非法字符 | PASS | `26-rename-invalid-input`、`27-rename-invalid-toast`（`文件名包含非法字符：'/'`，确定按钮禁用） |
| 13 | 重命名：合法名 | PASS | `28-rename-done`（a.txt → aa.txt） |
| 14 | 新建文件夹：非法名 | PASS | `35-newfolder-invalid`（`文件名包含非法字符：':'`） |
| 15 | 新建文件夹：合法 | PASS | `36-newfolder-done`（newdir/ 创建成功） |
| 16 | 新建文件 | PASS | `38-newfile-done`（newfile.txt 0 B） |
| 17 | 压缩 ZIP（嵌套目录） | PASS | `39-zip-dialog`、`40-zip-done`（nested.zip 291 B，条目 `nested/y.md`、`nested/deep/x.txt`） |
| 18 | 压缩 ZIP（中文名 + 空格） | PASS | `42-zip-cn-dialog`、`43-zip-cn-done`（`中文 名.zip` 条目名正确，UTF-8 未乱码） |
| 19 | 解压（中文名 zip） | PASS | `44-unzip-conflict`（操作完成，条目正确落盘） |
| 20 | Zip-Slip 防护（`../evil.txt`） | PASS | `47-zipslip`（操作失败：Invalid zip entry path；`/sdcard/qa-w2/evil.txt` 不存在，越界写入被拒） |
| 21 | 解压覆盖同名文件的行为 | **FAIL（设计不一致）** | `45-unzip-overwrite`（预置 MARKER 被静默覆盖，无冲突对话框）→ D2 |
| 22 | 编辑器：点 .txt 打开、UTF-8 显示、行号、状态栏 | PASS | `48-editor-utf8`（编码：UTF-8，行 3 列 1，21 字符） |
| 23 | 编辑器：编辑后保存 | PASS | `50-editor-saved`（Toast 已保存，磁盘内容变为 `second line EDITED`） |
| 24 | 编辑器：返回未保存确认 | PASS | `51-unsaved-confirm`（未保存的更改 / 继续编辑 / 放弃修改） |
| 25 | 编辑器：放弃修改不落盘 | PASS | `55-discard-tap`；`cat aa.txt` 仍为 `EDITED`（XYZ 未写入） |
| 26 | 编辑器：打开第二个文本文件（GBK） | **FAIL（阻断）** | `56-gbk-open-shows-aa`、`57-editor-reopen`（点 gbk.txt 仍显示 aa.txt 与旧缓冲区）→ D1 |
| 27 | 编辑器：GBK 编码识别 | 未覆盖（被 D1 阻断） | — |
| 28 | 分享入口（选择 → 更多 → 分享） | 未覆盖 | 时间原因未执行，见 §5 |
| 29 | UI 回归（面包屑/排序/隐藏/单窗格/抽屉/选择态） | PASS（抽检） | `01-launch`（W2 面包屑显示根 `/`，W1 的 D2 已修复）、`09-selection` |
| 30 | 稳定性：logcat 无 FATAL/ANR | PASS（部分） | 全程 `logcat` 无 `FATAL EXCEPTION` / `ANR in`；PID 10339 自 21:52 起未变（未做 2 分钟连续压测） |

通过率（已执行项）：**25 / 27 = 92.6%**（2 项 FAIL，均为缺陷 D1/D2；另 2 项未覆盖）。

## 3. 缺陷

### D1 [P2 · 编辑器] 编辑器会话不复位：打开第二个文本文件仍显示上一个文件与旧缓冲区

- 复现：
  1. 打开 `aa.txt` → 编辑（追加 `XYZ`）不保存 → 返回 → 弹「未保存的更改」→ 放弃修改 → 回到浏览器
  2. 在浏览器点同一目录下的 `gbk.txt`
- 期望：编辑器打开 `gbk.txt`（标题/路径/内容/编码均为 gbk.txt）
- 实际：编辑器仍显示 `aa.txt` 的标题与路径（`/storage/emulated/0/qa-w2/src/aa.txt`）、内容为**被放弃的旧缓冲区**（`EDITEDXYZ`）、编码显示 UTF-8，并弹出「已保存」Toast；此时按返回又会弹「未保存的更改」，形成循环
- 磁盘核对：`aa.txt` = `hello w2/second line EDITED`（XYZ 未落盘），`gbk.txt` 仍为 GBK 字节 `c4e3 bac3 …`（未被写入）
- 影响：无法打开第二个文本文件；若用户在“已保存”提示下继续编辑并保存，存在**写错文件**的风险 —— 这是 GBK 用例无法执行的原因
- 截图：`qa-w2-52-editor-stale-session.png`、`qa-w2-56-gbk-open-shows-aa.png`、`qa-w2-57-editor-reopen.png`
- 线索（未改代码）：`EditorViewModel` 以单例/复用方式持有会话，进入编辑器时未见 `filePath` 变化即重建状态的逻辑；建议在 `EditorScreen` 的 `LaunchedEffect(filePath)` 中重置 buffer/dirty/encoding。

### D2 [P3 · 一致性] 解压同名文件直接静默覆盖，无冲突分支

- 复现：`中文 名.zip` 与同名 `中文 名.txt` 同目录 → 选中 zip → 更多 → 解压
- 期望：与复制/移动一致走冲突对话框（覆盖/跳过/重命名/取消），或至少有覆盖提示
- 实际：直接覆盖（预置 `MARKER-OVERWRITE-TEST` 被 zip 内内容替换），仅提示「操作完成」
- 依据：`LocalFileOperator.runUnzip` 对每个 entry 使用 `CREATE + TRUNCATE_EXISTING`，未接入 `ConflictPolicy`
- 截图：`qa-w2-45-unzip-overwrite.png`
- 影响：可能静默丢失同名文件；P3（非崩溃，但属数据安全一致性问题）

### D3 [P3 · 文案] Zip-Slip 拒绝时错误信息为英文原文

- 复现：解压含 `../evil.txt` 的压缩包
- 实际：Toast `操作失败：Invalid zip entry path: ../evil.txt`（Java 异常原文直出，未本地化）
- 期望：中文提示，如「压缩包内路径非法，已拒绝解压」
- 截图：`qa-w2-47-zipslip.png`

### D4 [P3 · UI] 进度对话框的项数落后于百分比

- 复现：复制 1.2 GB 单文件
- 实际：`68%` 时仍显示 `0 / 1 项`（项计数只在单项完成后 +1）
- 期望：进行中显示 `0.7 / 1 项` 或隐藏项数只留百分比
- 截图：`qa-w2-20-progress.png`

### 观察（不计缺陷）

- 单文件 100 MB 复制 < 0.7 s，无法在中途取消；改用 1.2 GB 才得到可操作的取消窗口（模拟器共享盘带宽高）。
- 解压含非法路径的包时，同包内合法条目也不会解出（fail-closed），符合安全预期。
- W1 报告的 D2（深层路径面包屑首段 `/` 丢失）在 W2 已修复：现在始终可见根 `/`（`qa-w2-01-launch.png`）。

## 4. 截图索引（`docs/shots/`，44 张）

| 文件 | 内容 |
| --- | --- |
| `qa-w2-01-launch.png` | 冷启动；W2 新面包屑（含根 `/`、计数胶囊） |
| `qa-w2-04-qa-w2-right-active.png` | 右窗格激活，双窗格不同路径 |
| `qa-w2-07/08` | 左 src、右 dst 就绪 |
| `qa-w2-09-selection.png` | 选择 a.txt（已选 1 项） |
| `qa-w2-10-conflict.png` | 冲突对话框（取消/覆盖/跳过/重命名） |
| `qa-w2-11-conflict-cancel.png` | 分支·取消（目标未变） |
| `qa-w2-13-replace-done.png` | 分支·覆盖 + 完成 Toast + 目标窗格刷新 |
| `qa-w2-14-skip.png` | 分支·跳过（成功 0 项，跳过 1 项） |
| `qa-w2-15-*` | 分支·重命名（`a (1).txt`，见下方 ls 记录）——截图内容为完成后的列表 |
| `qa-w2-16/17` | 移动选择态 / 移动完成 |
| `qa-w2-18/19/20` | 大文件准备与进度（68%） |
| `qa-w2-21-cancelled.png` | 取消后「已取消操作」，无残留 |
| `qa-w2-22/23` | 删除确认 / 删除完成 |
| `qa-w2-24-more-sheet.png` | 更多菜单（重命名/压缩/解压置灰/分享） |
| `qa-w2-25/26/27/28` | 重命名对话框 / 非法字符 / 校验文案 / 改名成功 |
| `qa-w2-33/34/35/36` | 新建菜单 / 新建文件夹对话框 / 非法名校验 / 创建成功 |
| `qa-w2-37/38` | 新建文件对话框 / 创建成功 |
| `qa-w2-39/40` | 压缩为 ZIP 对话框 / 完成 |
| `qa-w2-41/42/43` | 中文名列表 / 中文压缩对话框 / 压缩完成 |
| `qa-w2-44-unzip-conflict.png` | 解压完成（同名已存在） |
| `qa-w2-45-unzip-overwrite.png` | D2：解压静默覆盖 |
| `qa-w2-47-zipslip.png` | Zip-Slip 拒绝（D3 英文文案） |
| `qa-w2-48-editor-utf8.png` | 编辑器打开 UTF-8 文件 |
| `qa-w2-50-editor-saved.png` | 编辑后保存（已保存） |
| `qa-w2-51-unsaved-confirm.png` | 未保存确认对话框 |
| `qa-w2-52/55` | 放弃修改前后的状态 |
| `qa-w2-56/57` | D1：点 gbk.txt 显示 aa.txt 会话 |

## 5. 未覆盖 / 顺延

1. **分享入口**：选择文件 → 更多 → 分享 → chooser / content URI 校验未执行（时间原因），建议下一轮补测，注意 FileProvider authority 与 `FLAG_GRANT_READ_URI_PERMISSION`。
2. **GBK 编码识别**：被 D1 阻断，D1 修复后必须重跑（`gbk.txt` 夹具已备好，期望状态栏显示「编码：GBK」且中文正常）。
3. **稳定性压测**：仅做了零散操作 + logcat 检查（无 FATAL/ANR，PID 未重启），未做 2 分钟连续操作压测。
4. **性能/异常**：10 MB 文件只读预览、存储不足、跨分区移动、`/system` 只读写入（无 root，环境限制同 W1）未覆盖。
5. **解压嵌套 + 中文 + 冲突组合**：仅分别验证了「嵌套目录压缩」「中文名压缩/解压」，未做三者叠加。

## 6. 结论与建议

- W2 的文件操作引擎与 UI 主体质量良好：冲突四分支语义正确、取消后无临时文件残留、Zip-Slip 防护有效、重命名/新建校验前置（对话框内联报错 + 禁用确定），比 W1 有明显提升。
- 修复优先级：**D1（P2，编辑器会话复用，阻断 GBK 与多文件编辑）** → D2（解压冲突策略，安全一致性）→ D3/D4（文案与进度细节）。
- 回归口径：D1 修复后重跑「编辑不保存 → 放弃 → 打开另一个 txt → 内容/编码正确 → 保存只影响新文件」；D2 修复后重跑「同名解压四个分支」。
