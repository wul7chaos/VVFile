package com.vvfile.app.feature.browser

import com.vvfile.app.core.path.PathUtils

/** 批量重命名的输入条目。[isDirectory] 只影响扩展名拆分（文件夹名不再按 `.` 拆）。 */
data class RenameTarget(val name: String, val path: String, val isDirectory: Boolean)

/**
 * 批量重命名规则，只有两种——刻意不做 MT 那套 `{P}{S}{T}{E}{N}{zN}` 模板语言。
 *
 * - [Sequence]：一个输入框。留空表示「原名 + 序号」，填了就把整个主名换掉，
 *   其中的 `#` 换成序号（`##`/`###` 强制补零到 2/3 位，单个 `#` 按数量自动定位数）。
 * - [Replace]：查找 + 替换，只作用于主名（扩展名不动），区分大小写，全部替换。
 */
sealed interface RenameRule {
    data class Sequence(val template: String) : RenameRule
    data class Replace(val find: String, val replaceWith: String) : RenameRule
}

/** 预览里单条的问题；[UNCHANGED] 不算错误，只是没改动。 */
enum class RenameIssue { EMPTY, INVALID, DUPLICATE, EXISTS, UNCHANGED }

data class RenamePreview(val original: String, val result: String, val issue: RenameIssue?)

/** 该问题是否会阻断「应用」；null（一切正常）与 [RenameIssue.UNCHANGED] 都不阻断。 */
fun RenameIssue?.blocksApply(): Boolean = this != null && this in BatchRename.BLOCKING

/**
 * 批量重命名的纯逻辑：给规则 + 条目列表，算出每个新名字并标出问题。
 *
 * 全部是纯函数，不碰文件系统，单元测试直接跑（[BatchRenameTest]）。
 */
object BatchRename {

    /** 单个文件名最多 255 字节（ext4/FUSE 的硬限制），超了必然失败。 */
    private const val MAX_NAME_BYTES = 255

    /** 阻断应用的问题：有任意一条就不能点「应用」。 */
    val BLOCKING: Set<RenameIssue> = setOf(
        RenameIssue.EMPTY,
        RenameIssue.INVALID,
        RenameIssue.DUPLICATE,
        RenameIssue.EXISTS,
    )

    fun isApplicable(items: List<RenamePreview>): Boolean =
        items.isNotEmpty() && items.none { it.issue in BLOCKING }

    /**
     * 计算预览。
     *
     * @param targets 按列表顺序排列的条目，序号从 1 开始按这个顺序递增
     * @param existingNames 当前目录下所有条目的名字（含未被选中的），用于判重名
     */
    fun preview(
        targets: List<RenameTarget>,
        rule: RenameRule,
        existingNames: Set<String>,
    ): List<RenamePreview> {
        val autoWidth = autoWidth(targets.size)
        val raw = targets.mapIndexed { index, target -> target to newName(target, index + 1, rule, autoWidth) }
        val counts = raw.groupingBy { it.second }.eachCount()
        val targetNames = targets.mapTo(HashSet()) { it.name }
        return raw.map { (target, result) ->
            RenamePreview(
                original = target.name,
                result = result,
                issue = issueOf(target, result, counts, existingNames, targetNames),
            )
        }
    }

    /** 序号自动定位数：至少 2 位，条目多了跟着变宽。 */
    fun autoWidth(count: Int): Int = maxOf(2, count.coerceAtLeast(0).toString().length)

    /** 把名字拆成「主名 + 扩展名」；文件夹和点开头的隐藏文件不拆。 */
    fun splitName(name: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory) return name to ""
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    private fun newName(target: RenameTarget, number: Int, rule: RenameRule, autoWidth: Int): String {
        val (stem, extension) = splitName(target.name, target.isDirectory)
        val newStem = when (rule) {
            is RenameRule.Sequence -> {
                val template = rule.template.trim()
                when {
                    template.isEmpty() -> stem + pad(number, autoWidth)
                    '#' in template -> numberInto(template, number, autoWidth)
                    // 填了名称但没写 # ：说明是「统一改名」，自动补序号，否则整批同名
                    else -> template + pad(number, autoWidth)
                }
            }

            is RenameRule.Replace -> {
                if (rule.find.isEmpty()) stem else stem.replace(rule.find, rule.replaceWith)
            }
        }
        return newStem + extension
    }

    private fun pad(number: Int, width: Int): String = number.toString().padStart(width, '0')

    /**
     * 把模板里每一段连续的 `#` 换成序号。
     *
     * 一段只有一个 `#` 时用自动位数（14 项 → 2 位）；写了 `##` 就是 2 位，`####` 就是 4 位。
     */
    private fun numberInto(template: String, number: Int, autoWidth: Int): String {
        val builder = StringBuilder()
        var last = 0
        for (match in HASH_RUN.findAll(template)) {
            builder.append(template, last, match.range.first)
            val width = if (match.value.length >= 2) match.value.length else autoWidth
            builder.append(pad(number, width))
            last = match.range.last + 1
        }
        builder.append(template, last, template.length)
        return builder.toString()
    }

    private fun issueOf(
        target: RenameTarget,
        result: String,
        counts: Map<String, Int>,
        existingNames: Set<String>,
        targetNames: Set<String>,
    ): RenameIssue? = when {
        result.isBlank() -> RenameIssue.EMPTY
        result.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES -> RenameIssue.INVALID
        !PathUtils.isValidFileName(result) -> RenameIssue.INVALID
        (counts[result] ?: 0) > 1 -> RenameIssue.DUPLICATE
        // 目录里已有这个名字、且它本身不在本批次里（批内互换允许）
        result in existingNames && result !in targetNames -> RenameIssue.EXISTS
        result == target.name -> RenameIssue.UNCHANGED
        else -> null
    }

    private val HASH_RUN = Regex("#+")
}
