package com.vvfile.app.core.path

/**
 * 路径字符串工具：只做纯字符串运算，**不触碰文件系统**。
 *
 * 约定：
 * - 目标平台是 Android（POSIX），规范形式统一用 `/` 分隔；输入中的 `\` 会被视为分隔符，
 *   便于 JVM 单测与桌面调试时复用同一套逻辑。
 * - 相对路径的 `..` 在无法消解时保留（`a/../../b` → `../b`），绝对路径的 `..` 不会越过根。
 * - 不处理 UNC（`\\host\share`）与 `~` 展开，Android 上不需要。
 */
object PathUtils {

    private const val SEPARATOR = '/'

    /** 单个文件名中不允许出现的字符。 */
    private val INVALID_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 规范化路径：统一分隔符、合并重复分隔符、去掉 `.` 与可消解的 `..`、去掉末尾分隔符
     * （根目录除外）。
     *
     * 例：`/storage//emulated/./0/` → `/storage/emulated/0`；
     *     `C:\Users\x\..\y` → `C:/Users/y`；`a/..` → `.`。
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) return ""
        val unified = path.replace('\\', SEPARATOR)
        val (prefix, rest) = splitPrefix(unified)
        val parts = ArrayList<String>()
        for (segment in rest.split(SEPARATOR)) {
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (parts.isNotEmpty() && parts.last() != "..") {
                        parts.removeAt(parts.lastIndex)
                    } else if (prefix.isEmpty()) {
                        parts.add("..")
                    }
                }
                else -> parts.add(segment)
            }
        }
        val body = parts.joinToString(SEPARATOR.toString())
        return when {
            prefix.isEmpty() -> body.ifEmpty { "." }
            prefix == SEPARATOR.toString() -> if (body.isEmpty()) SEPARATOR.toString() else "$SEPARATOR$body"
            else -> if (body.isEmpty()) "$prefix$SEPARATOR" else "$prefix$SEPARATOR$body"
        }
    }

    /**
     * 父路径；根目录、盘符根、单段相对路径返回 `null`。
     *
     * 例：`/a/b` → `/a`；`/a` → `/`；`/` → `null`；`C:/Users` → `C:/`。
     */
    fun parentOf(path: String): String? {
        val normalized = normalize(path)
        if (normalized.isEmpty() || normalized == "." || normalized == SEPARATOR.toString()) return null
        if (isDriveRoot(normalized)) return null
        val trimmed = normalized.trimEnd(SEPARATOR)
        val index = trimmed.lastIndexOf(SEPARATOR)
        if (index < 0) return null
        if (index == 0) return SEPARATOR.toString()
        val parent = trimmed.substring(0, index)
        return if (isDrivePrefix(parent)) "$parent$SEPARATOR" else parent
    }

    /**
     * 拼接路径。`name` 是绝对路径时直接返回其规范形式；`name` 为空时返回 base 的规范形式。
     *
     * 例：`join("/a", "b")` → `/a/b`；`join("/", "b")` → `/b`；`join("/a", "/x")` → `/x`。
     */
    fun join(base: String, name: String): String {
        if (name.isEmpty()) return normalize(base)
        if (name.startsWith(SEPARATOR) || name.startsWith('\\')) return normalize(name)
        val normalizedBase = normalize(base)
        if (normalizedBase.isEmpty() || normalizedBase == ".") return normalize(name)
        val prefix = if (normalizedBase.endsWith(SEPARATOR)) normalizedBase else normalizedBase + SEPARATOR
        return normalize(prefix + name)
    }

    /** 末段名称；根目录、空路径返回空串。`/a/b/` → `b`。 */
    fun fileName(path: String): String {
        val normalized = normalize(path)
        return when {
            normalized.isEmpty() || normalized == SEPARATOR.toString() || normalized == "." -> ""
            else -> normalized.substringAfterLast(SEPARATOR)
        }
    }

    /**
     * 小写扩展名，不含点；没有扩展名或名称为空返回空串。
     *
     * 语义与 [com.vvfile.app.data.model.FileEntry.extension] 保持一致（点开头视为扩展名的一部分，
     * `.gitignore` → `gitignore`），避免 UI 图标与工具层判断出现两套规则。
     */
    fun extensionOf(name: String): String {
        if (name.isEmpty()) return ""
        val index = name.lastIndexOf('.')
        if (index < 0 || index == name.lastIndex) return ""
        return name.substring(index + 1).lowercase()
    }

    /** 以 `.` 开头的名称视为隐藏项；`.` 与 `..` 不算隐藏项。 */
    fun isHidden(path: String): Boolean {
        val name = fileName(path)
        if (name.isEmpty() || name == "." || name == "..") return false
        return name.startsWith('.')
    }

    /**
     * `ancestor` 是否为 `descendant` 的上级目录（不含自身）。
     *
     * 例：`isAncestor("/a", "/a/b")` → true；`isAncestor("/a", "/ab")` → false。
     */
    fun isAncestor(ancestor: String, descendant: String): Boolean {
        val parent = normalize(ancestor)
        val child = normalize(descendant)
        if (parent.isEmpty() || child.isEmpty() || parent == child) return false
        val prefix = if (parent.endsWith(SEPARATOR)) parent else parent + SEPARATOR
        return child.startsWith(prefix)
    }

    /**
     * 校验单个文件名（不含路径分隔符），合法时原样返回，非法抛 [IllegalArgumentException]。
     *
     * 拒绝：空名/纯空白、`.`、`..`、`/ \ : * ? " < > |`、控制字符（0x00–0x1F、0x7F）。
     */
    fun validateFileName(name: String): String {
        require(name.isNotEmpty()) { "文件名不能为空" }
        require(name.isNotBlank()) { "文件名不能只包含空白字符" }
        require(name != "." && name != "..") { "文件名不能是 \"$name\"" }
        for (char in name) {
            require(char !in INVALID_NAME_CHARS) { "文件名包含非法字符：'$char'" }
            require(char.code >= 0x20 && char.code != 0x7F) { "文件名包含控制字符（0x${char.code.toString(16)}）" }
        }
        return name
    }

    /** [validateFileName] 的布尔形式，供 UI 输入框即时校验。 */
    fun isValidFileName(name: String): Boolean = runCatching { validateFileName(name) }.isSuccess

    private fun splitPrefix(unified: String): Pair<String, String> {
        if (unified.length >= 2 && unified[0].isLetter() && unified[1] == ':') {
            return unified.substring(0, 2) to unified.substring(2)
        }
        return if (unified.startsWith(SEPARATOR)) SEPARATOR.toString() to unified else "" to unified
    }

    private fun isDriveRoot(path: String): Boolean =
        path.length == 3 && path[0].isLetter() && path[1] == ':' && path[2] == SEPARATOR

    private fun isDrivePrefix(path: String): Boolean =
        path.length == 2 && path[0].isLetter() && path[1] == ':'
}
