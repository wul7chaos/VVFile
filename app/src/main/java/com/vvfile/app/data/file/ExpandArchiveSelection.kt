package com.vvfile.app.data.file

/**
 * 「只解压选中的几条」时把选择集展开成实际要落的条目。
 *
 * 选择只可能是包内相对路径（`a/b.txt`、目录 `a/b`）。规则对齐 MT：
 * - 选中一条文件 → 就解这一条；
 * - 选中一条目录 → 它底下的所有条目一起出来（否则只会建出一个空目录）；
 * - 选中的名字在包里根本不存在（包被改过）→ 按字面量匹配，宁可不匹配也不要全解。
 *
 * 展开放在这里而不是 UI 里，是因为三种容器（ZIP / 7z / RAR）共用同一套语义，
 * 单测也只需要喂名字列表。
 */
object ExpandArchiveSelection {

    /** 把选择集展开成前缀集合，末尾统一带 `/`，方便按前缀比。 */
    fun expand(selected: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        selected.forEach { raw ->
            val name = raw.trim().trimStart('/')
            if (name.isEmpty()) return@forEach
            out += if (name.endsWith("/")) name else name
        }
        return out.toList()
    }

    /**
     * 过滤出真正要解的条目。
     *
     * [wanted] 为 null 或空表示整包。目录条目按前缀包含：`a` 会带出 `a/b.txt`；
     * 同时保留包内路径上层的目录条目，避免解到一半缺目录。
     */
    fun <T> filter(entries: List<T>, wanted: List<String>?, nameOf: (T) -> String): List<T> {
        if (wanted.isNullOrEmpty()) return entries
        // 选择集里全是空串（只有分隔符）时等价于没选：宁可按整包解，也不要解出个空目录
        val paths = expand(wanted)
        if (paths.isEmpty()) return entries
        return entries.filter { entry ->
            val name = nameOf(entry).trimStart('/')
            paths.any { path ->
                name == path ||
                    name == path.trimEnd('/') ||
                    name.startsWith(path.trimEnd('/') + "/")
            }
        }
    }
}
