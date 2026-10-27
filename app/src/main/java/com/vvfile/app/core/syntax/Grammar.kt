package com.vvfile.app.core.syntax

import org.json.JSONObject

/**
 * TextMate 语法的内存模型：只保留高亮用得到的部分。
 *
 * 支持 `match` / `begin`+`end` / `include`（`#repo`、`$self`、`$base`、外部 scope）
 * 与 `captures`；`while`、injection 这类复杂特性不实现——手机上看个颜色用不上。
 */
class Grammar internal constructor(
    val scopeName: String,
    val patterns: List<ScopeRule>,
    val repository: Map<String, List<ScopeRule>>,
) {
    /** `#name` 形式的局部引用，找不到就返回 null（语法里偶尔引用了没定义的分支）。 */
    fun repo(name: String): List<ScopeRule>? = repository[name]
}

/** 一条语法规则；`match` 与 `begin` 只会有一个非空。 */
class ScopeRule internal constructor(
    val name: String?,
    val contentName: String?,
    val match: Regex?,
    val begin: Regex?,
    val end: Regex?,
    val `while`: Regex?,
    val patterns: List<ScopeRule>?,
    val include: String?,
    val captures: Map<Int, String>,
    val beginCaptures: Map<Int, String>,
    val endCaptures: Map<Int, String>,
)

object GrammarParser {

    fun parse(scopeName: String, json: String): Grammar {
        val root = JSONObject(json)
        val repository = HashMap<String, List<ScopeRule>>()
        root.optJSONObject("repository")?.let { repo ->
            for (key in repo.keys()) {
                val value = repo.optJSONObject(key) ?: continue
                // repository 里的条目本身就是一条规则；只有「纯容器」（只有 patterns 没有
                // match/begin/include）才展开成规则列表
                val rule = parseRule(value)
                val container = rule.match == null && rule.begin == null &&
                    rule.include == null && rule.patterns != null
                repository[key] = if (container) rule.patterns!! else listOf(rule)
            }
        }
        val patterns = parseRules(root)
        return Grammar(scopeName, patterns, repository)
    }

    private fun parseRules(o: JSONObject): List<ScopeRule> {
        val arr = o.optJSONArray("patterns") ?: return emptyList()
        val out = ArrayList<ScopeRule>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.add(parseRule(item))
        }
        return out
    }

    private fun parseRule(o: JSONObject): ScopeRule = ScopeRule(
        name = o.optString("name").takeIf { it.isNotEmpty() },
        contentName = o.optString("contentName").takeIf { it.isNotEmpty() },
        match = o.optString("match").takeIf { it.isNotEmpty() }?.let(::regex),
        begin = o.optString("begin").takeIf { it.isNotEmpty() }?.let(::regex),
        end = o.optString("end").takeIf { it.isNotEmpty() }?.let(::regex),
        `while` = o.optString("while").takeIf { it.isNotEmpty() }?.let(::regex),
        patterns = o.optJSONArray("patterns")?.let { parseRules(o) },
        include = o.optString("include").takeIf { it.isNotEmpty() },
        captures = captures(o.optJSONObject("captures")),
        beginCaptures = captures(o.optJSONObject("beginCaptures")),
        endCaptures = captures(o.optJSONObject("endCaptures")),
    )

    private fun captures(o: JSONObject?): Map<Int, String> {
        if (o == null) return emptyMap()
        val out = HashMap<Int, String>()
        for (key in o.keys()) {
            val idx = key.toIntOrNull() ?: continue
            val name = o.optJSONObject(key)?.optString("name").orEmpty()
            if (name.isNotEmpty()) out[idx] = name
        }
        return out
    }

    /**
     * 编译一条规则。
     *
     * 必须默认打开 MULTILINE：Oniguruma（TextMate 用的引擎）里 `^`/`$` 本来就是行锚点，
     * Java 默认是整串锚点，不开的话 `//.*$` 这类行注释只能匹配到文件最后一行。
     * 编译不过的（Oniguruma 独有写法）直接丢掉这条规则，不影响整份语法。
     */
    /** 编译失败的规则数（诊断用，UI 不读）。 */
    internal val failed = LinkedHashMap<String, Int>()

    private fun regex(pattern: String): Regex? = try {
        Regex(normalize(compat(pattern)), setOf(RegexOption.MULTILINE))
    } catch (e: Throwable) {
        synchronized(failed) {
            val key = pattern.take(80) + "  // " + (e.message ?: "").take(60)
            failed[key] = (failed[key] ?: 0) + 1
        }
        null
    }

    /** Oniguruma 允许裸的 `{`（比如 `(?={)`），Java 会当成量词报错，这里补上反斜杠。 */
    private val BARE_BRACE = Regex("\\{(?!\\d+(?:,\\d*)?\\})")

    private fun compat(pattern: String): String {
        if (!pattern.contains('{')) return pattern
        val holder = "\u0001"
        val kept = pattern.replace("\\{", holder)
        // 用 lambda 形式替换：字符串替换里 `\{` 会被当成转义序列吃掉反斜杠
        return BARE_BRACE.replace(kept) { "\\{" }.replace(holder, "\\{")
    }

    /** POSIX 字符类（Oniguruma 支持，Java 不认）换成等价的 Unicode 属性。 */
    private val POSIX = mapOf(
        "[:alpha:]" to "\\p{Alpha}",
        "[:alnum:]" to "\\p{Alnum}",
        "[:digit:]" to "\\p{Digit}",
        "[:space:]" to "\\p{Space}",
        "[:xdigit:]" to "\\p{XDigit}",
        "[:upper:]" to "\\p{Upper}",
        "[:lower:]" to "\\p{Lower}",
        "[:punct:]" to "\\p{Punct}",
        "[:cntrl:]" to "\\p{Cntrl}",
        "[:blank:]" to "\\p{Blank}",
        "[:print:]" to "\\p{Print}",
        "[:graph:]" to "\\p{Graph}",
        "[:word:]" to "\\p{Alnum}_",
    )

    private fun normalize(pattern: String): String {
        var p = pattern
        if (p.contains("[:") ) {
            for ((from, to) in POSIX) p = p.replace(from, to)
        }
        return p
    }
}
