# 语法高亮（抄 VS Code 的规则）

编辑器的高亮不再用第三方高亮库，而是直接吃 **VS Code 的 TextMate 语法**：
`design/syntax/pull.py` 从本机 VS Code 源码里按白名单拷出 `.tmLanguage.json`，
运行时由 `app/src/main/java/com/vvfile/app/core/syntax/` 里的自写引擎分词。

## 链路

| 步骤 | 文件 | 说明 |
| --- | --- | --- |
| 拷语法 | [pull.py](pull.py) | 从 `<仓库>/vscode/extensions/*/syntaxes/` 拷进 `app/src/main/assets/syntax/grammars/` |
| 生成清单 | 同上 | 同时写出 `SyntaxManifest.kt`（扩展名 → scopeName、scopeName → 文件名） |
| 解析 | `core/syntax/Grammar.kt` | `.tmLanguage.json` → 规则树；正则编译失败的单条规则丢弃 |
| 分词 | `core/syntax/TextMateTokenizer.kt` | `begin`/`end`/`while`/`match`/`include`/`captures` 子集 |
| 上色 | `core/syntax/ScopeTheme.kt` | scope 前缀 → 颜色/粗细/斜体 |
| 接入 | `feature/editor/EditorSyntax.kt` | 编辑器只认这一个入口 |

```powershell
# 默认读 <仓库>/vscode，也可以显式指定 VS Code 源码目录
python design\syntax\pull.py
```

`vscode/` 是本地参考用的源码 checkout，已进 `.gitignore`，不入库。

## 只实现了 TextMate 的一个子集

| 特性 | 支持 | 说明 |
| --- | --- | --- |
| `match` / `begin` + `end` | ✅ | 主体的单行、多行结构 |
| `begin` + `while` | ⚠️ 近似 | 没有 `end` 的 `while` 规则按「行尾结束」处理 |
| `include`：`#repo` / `$self` / `$base` / 外部 scope | ✅ | 外部 scope 按 scopeName 从资产里按需再加载 |
| `captures` / `beginCaptures` / `endCaptures` | ✅ | 只为着色，不做括号配对 |
| `injections` / `embeddedLanguages` / `applyEndPatternLast` | ❌ | 手机上看颜色用不到 |

正则兼容层（`Grammar.kt` 的 `compat()` / `normalize()`）：

- 默认打开 **MULTILINE**：Oniguruma 的 `^`/`$` 本来就是行锚点，Java 默认是整串锚点
- 裸的 `{`（`(?={)`）补 `\{`，否则 Java 当量词报错
- POSIX 字符类 `[[:alpha:]]` 换成 `\p{Alpha}` 一类

## 语言与取舍

- 绝大多数语言直接映射到 VS Code 的语法：java/js/ts/c/cpp/cs/py/rb/pl/php/go/rs/swift/dart/sh、
  json/xml/html/css/scss/less/md/yaml/ini/groovy/bat/ps1/lua/sql/clojure/coffee/fsharp/julia/r/make/diff/docker/log
- **C++ 借 C 的语法**：`cpp.tmLanguage.json` 有 682 KB，依赖不少 Java 正则支持不了的写法，
  实测一半文本不上色；C 的规则反而干净
- **Kotlin 自己写**：VS Code 没有内置 Kotlin 语法，[kotlin.tmLanguage.json](kotlin.tmLanguage.json)
  按 Kotlin 语言规范写了一份，结构与其它语法一致
- `txt` / `log`（文本日志走纯文本）/ `csv` 不映射，避免做成花色文本

## 性能

- 上限 `SyntaxHighlighter.MAX_CHARS = 40_000`，超了直接不着色
- 分词在 `Dispatchers.Default` 上跑，输入一变就取消上一轮（`produceState`）
- 引擎按「找最近的下一个匹配」推进，每条规则记住上次命中位置（memo），
  17.8 K 字符的 Kotlin 在 CI 的 JVM 上约 40 ms；手机上大概 100~200 ms，只在停止输入后出颜色

## 已知差异

- 不是 100% 的 VS Code：`injections` 没实现，少数极端语法会有局部颜色缺失
- 颜色是我们自己的一套（见 `04-UI设计规范.md` §8.1），不是 VS Code 主题
- 单测里 `GrammarCoverageTest` 会把 39 份语法全部跑一遍，保证「分词不吞字」；
  `GrammarTokenizerTest` 守住 Kotlin 的关键字/注释/字符串/数字与速度
