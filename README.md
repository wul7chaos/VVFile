# VVFile

面向 Android 的本地文件管理器，双窗格工作流，专注文件管理与 root / 终端能力。
不含 APK 修改、会员、插件、回收站、Activity 记录、MCP。

- 包名：`com.vvfile.app`（debug 变体 `com.vvfile.app.debug`）
- 技术栈：Kotlin · Jetpack Compose · Material 3
- 最低版本：Android 8.0（API 26），targetSdk 36
- 当前阶段：**M1 浏览与选择态（W1 已合并）**；W1 回归见 [docs/qa/w1-report.md](docs/qa/w1-report.md)

## 文档

| 文档 | 内容 |
| --- | --- |
| [00-项目概述](docs/00-项目概述.md) | 定位、目标、非目标、术语 |
| [01-功能范围](docs/01-功能范围.md) | P0/P1/P2 与排除项、验收口径 |
| [02-架构设计](docs/02-架构设计.md) | 分层、双窗格状态、操作模型、root 设计 |
| [03-目录结构](docs/03-目录结构.md) | 目标目录树与命名约定 |
| [04-UI设计规范](docs/04-UI设计规范.md) | 配色、布局、组件、与参考产品的区隔 |
| [05-构建与运行](docs/05-构建与运行.md) | 环境、构建、MuMu 连接、安装 |
| [06-测试计划](docs/06-测试计划.md) | 测试矩阵与回归清单 |
| [07-路线图](docs/07-路线图.md) | M0–M5 里程碑与版本 |
| [08-子代理并行开发规范](docs/08-子代理并行开发规范.md) | 写作用域、worktree、任务板、合并规则 |

## 快速开始

```powershell
# 1. 构建
.\gradlew.bat :app:assembleDebug

# 2. 连接 MuMu（自动读取 adb 端口）
.\scripts\adb-connect.ps1

# 3. 安装并启动
.\scripts\install.ps1
```

首次启动需授予「所有文件访问权限」；也可用：

```powershell
adb shell appops set com.vvfile.app.debug MANAGE_EXTERNAL_STORAGE allow
```

## 参考声明

`docs/refs/` 中的截图仅用于功能盘点与交互对照，来自本机测试环境中的同类产品。
VVFile 不使用其图标、文案、代码或其它受版权保护的素材；界面与配色为独立设计。
