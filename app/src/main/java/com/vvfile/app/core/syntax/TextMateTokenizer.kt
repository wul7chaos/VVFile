package com.vvfile.app.core.syntax

import java.util.regex.Matcher

/**
 * TextMate 语法分词器（子集实现）。
 *
 * 和 `vscode-textmate` 的差别：不实现 `while`、injection、embedded language 这些
 * 交互式编辑器才需要的特性，只按 `begin`/`end`/`match`/`include`/`captures` 切 token。
 *
 * 做法是「找最近的下一个匹配」而不是逐字符试探：每一帧里对每条规则 `find(pos)`，
 * 取最早的那个位置，中间整段算纯文本。这样每切一个 token 是 O(规则数)，
 * 40 K 字符的文件在手机上也是几十毫秒级，不至于每敲一键都卡。
 */
class TextMateTokenizer(private val registry: GrammarRegistry) {

    private companion object {
        /** 给 begin+while 规则合成出来的「行尾」结束符。 */
        val WHILE_END = Regex("(?=\\n)")
    }

    /** 一段带 scope 的文本区间；`scope` 为 null 表示按正文色。 */
    data class Token(val start: Int, val end: Int, val scope: String?)

    private class Compiled(
        val rule: ScopeRule,
        val owner: Grammar,
        val rules: List<Compiled>?,
    ) {
        private var matchM: Matcher? = null
        private var beginM: Matcher? = null

        // 每条规则记住「上一次搜到的命中位置」：位置没被吐出去之前不用再跑正则，
        // 否则每个 token 都从 pos 搜到文件尾，40 K 字符会退化成平方级。
        var memo: MatchData? = null
        var memoFrom = -1
        var memoRun = 0

        fun matcher(kind: Int, text: CharSequence): Matcher? {
            val p = when (kind) {
                0 -> rule.match
                1 -> rule.begin
                else -> null
            } ?: return null
            val cached = if (kind == 0) matchM else beginM
            if (cached != null) return cached
            val fresh = p.toPattern().matcher(text)
            if (kind == 0) matchM = fresh else beginM = fresh
            return fresh
        }
    }

    private class Frame(
        val rules: List<Compiled>,
        val end: Matcher?,
        val endCaptures: Map<Int, String>,
        val endName: String?,
        val defaultScope: String?,
        /** `while`：end 命中后若它在同一位置也命中，这一帧继续（TextMate 的括号配对写法） */
        val whileMatcher: Matcher? = null,
        val whileCaptures: Map<Int, String> = emptyMap(),
    )

    private var runId = 0

    fun tokenize(scopeName: String, text: String): List<Token> {
        val grammar = registry.grammar(scopeName) ?: return emptyList()
        return run(grammar, grammar.patterns, text)
    }

    /** 直接喂语法对象和顶层规则，单测用（不依赖 assets）。 */
    fun tokenize(grammar: Grammar, rules: List<ScopeRule>, text: String): List<Token> =
        run(grammar, rules, text)

    private fun run(grammar: Grammar, rules: List<ScopeRule>, text: String): List<Token> {
        runId++
        val root = flatten(grammar, rules, 0, HashSet())
        if (root.isEmpty()) return emptyList()

        val len = text.length
        val out = ArrayList<Token>(256)
        val stack = ArrayList<Frame>()
        stack.add(Frame(root, null, emptyMap(), null, null))
        var pos = 0
        var stalled = 0
        var lastPos = -1

        while (pos < len) {
            // 兜底：同一位置连续很多次没推进（语法里可能零宽 begin/end 互相弹栈），
            // 把当前字符当正文吐出去再前进，既不死循环也不丢字符
            if (pos == lastPos) {
                stalled++
                if (stalled > 32) {
                    stalled = 0
                    plain(out, pos, pos + 1, stack.lastOrNull()?.defaultScope)
                    pos++
                    continue
                }
            } else {
                lastPos = pos
                stalled = 0
            }
            val frame = stack[stack.size - 1]
            val endHit = find(frame.end, text, pos)
            val ruleHit = nextRule(frame.rules, text, pos)

            if (endHit != null && (ruleHit == null || endHit.start <= ruleHit.match.start)) {
                plain(out, pos, endHit.start, frame.defaultScope)
                emitCaptures(out, text, endHit, frame.endCaptures, frame.endName)
                val again = frame.whileMatcher?.let { find(it, text, endHit.end) }
                if (again != null && again.start == endHit.end) {
                    emitCaptures(out, text, again, frame.whileCaptures, frame.defaultScope)
                    pos = advance(pos, again.end)
                    continue
                }
                stack.removeAt(stack.size - 1)
                // end 是零宽（比如 `(?=\})`）时不能跳字符，靠出栈保证前进
                if (endHit.end > pos) pos = endHit.end
                continue
            }
            if (ruleHit == null) {
                plain(out, pos, len, frame.defaultScope)
                break
            }

            plain(out, pos, ruleHit.match.start, frame.defaultScope)
            val compiled = ruleHit.rule
            val rule = compiled.rule
            when {
                rule.match != null -> {
                    emitCaptures(out, text, ruleHit.match, rule.captures, rule.name)
                    pos = advance(pos, ruleHit.match.end)
                }
                rule.begin != null -> {
                    // TextMate 里 begin/end 的 name 覆盖整段（含内容与结束符），
                    // contentName 只是内容再叠一层
                    val beginScope = resolveScope(frame.defaultScope, rule.name)
                    emitCaptures(out, text, ruleHit.match, rule.beginCaptures, beginScope)
                    // 没有 patterns 的 begin/end，内容按纯文本处理（TextMate 语义）；
                    // 不能拿语法根规则去套，否则注释里会被当代码再扫一遍
                    val sub = rule.patterns?.let { flatten(compiled.owner, it, 1, HashSet()) }
                        ?: emptyList()
                    // begin + while 没有 end 的写法（YAML、C++ 常见）：把「行尾」当结束，
                    // 近似 while 的逐行续帧语义，颜色够用且不会一口吞掉整份文件
                    val endPattern = rule.end ?: if (rule.`while` != null) WHILE_END else null
                    val endMatcher = endPattern?.let { m ->
                        m.toPattern().matcher(text).also {
                            it.useAnchoringBounds(false)
                            it.useTransparentBounds(true)
                        }
                    }
                    val whileMatcher = rule.`while`?.let { m ->
                        m.toPattern().matcher(text).also {
                            it.useAnchoringBounds(false)
                            it.useTransparentBounds(true)
                        }
                    }
                    stack.add(
                        Frame(
                            rules = sub,
                            end = endMatcher,
                            endCaptures = rule.endCaptures,
                            endName = beginScope,
                            defaultScope = resolveScope(beginScope, rule.contentName),
                            whileMatcher = whileMatcher,
                        ),
                    )
                    pos = advance(pos, ruleHit.match.end)
                }
                else -> pos = advance(pos, ruleHit.match.end)
            }
        }
        return merge(out)
    }

    // ------------------------------------------------------------------ 规则展开

    private class Hit(val rule: Compiled, val match: MatchData)


    private fun nextRule(rules: List<Compiled>, text: String, pos: Int): Hit? {
        var best: Hit? = null
        for (rule in rules) {
            val m = nextMatch(rule, text, pos) ?: continue
            // 纯 match 规则的零宽命中没东西可着色，跳过（否则要靠吞字符前进，
            // 正文会被吃出空洞）；但零宽 begin 是语法里真实存在的写法
            // （典型如 `(?=const)` 开一段 var 声明），必须放行
            if (m.start == m.end && rule.rule.match != null) continue
            if (best == null || m.start < best.match.start) {
                best = Hit(rule, m)
                if (m.start == pos) break
            }
        }
        return best
    }

    /** 带 memo 的查找：命中位置还在 pos 之后就直接复用，避免每个 token 重扫全文。 */
    private fun nextMatch(rule: Compiled, text: String, pos: Int): MatchData? {
        if (rule.memoRun == runId) {
            val cached = rule.memo
            if (cached != null) {
                if (cached.start >= pos) return cached
            } else if (pos >= rule.memoFrom) {
                return null
            }
        }
        val data = find(rule.matcher(0, text), text, pos) ?: find(rule.matcher(1, text), text, pos)
        rule.memo = data
        rule.memoFrom = pos
        rule.memoRun = runId
        return data
    }

    private fun flatten(
        grammar: Grammar,
        rules: List<ScopeRule>,
        depth: Int,
        seen: MutableSet<String>,
    ): List<Compiled> {
        if (depth > 6) return emptyList()
        val out = ArrayList<Compiled>(rules.size)
        for (rule in rules) {
            val include = rule.include
            if (include == null) {
                // 半截规则（begin 编译成功、end 没编译出来）会让帧永远关不上，
                // 一口气吞掉后面所有文本，宁可整条不要
                if (rule.begin != null && rule.end == null && rule.`while` == null) continue
                if (rule.match == null && rule.begin == null) continue
            }
            if (include == null) {
                out.add(Compiled(rule, grammar, null))
                continue
            }
            val key = grammar.scopeName + "|" + include
            if (!seen.add(key)) continue
            val target: Pair<Grammar, List<ScopeRule>>? = when {
                include == "\$self" || include == "\$base" -> grammar to grammar.patterns
                include.startsWith("#") -> {
                    val list = grammar.repo(include.substring(1))
                    if (list != null) grammar to list else null
                }
                else -> {
                    val scope = include.substringBefore('#')
                    val repo = include.substringAfter('#', "")
                    val g = registry.grammar(scope)
                    when {
                        g == null -> null
                        repo.isEmpty() -> g to g.patterns
                        else -> g.repo(repo)?.let { g to it }
                    }
                }
            }
            if (target != null) out.addAll(flatten(target.first, target.second, depth + 1, seen))
            seen.remove(key)
        }
        return out
    }

    // ------------------------------------------------------------------ 匹配

    private class MatchData(
        val start: Int,
        val end: Int,
        val groups: List<Pair<Int, IntRange>>,
    )

    private fun find(matcher: Matcher?, text: String, pos: Int): MatchData? {
        matcher ?: return null
        matcher.region(pos, text.length)
        matcher.useAnchoringBounds(false)
        matcher.useTransparentBounds(true)
        if (!matcher.find()) return null
        val start = matcher.start()
        val end = matcher.end()
        val groups = ArrayList<Pair<Int, IntRange>>(matcher.groupCount() + 1)
        for (g in 0..matcher.groupCount()) {
            val s = matcher.start(g)
            val e = matcher.end(g)
            if (s >= 0 && e >= s) groups.add(g to (s until e))
        }
        return MatchData(start, end, groups)
    }

    private fun resolveScope(parent: String?, child: String?): String? = when {
        child == null || child.isBlank() -> parent
        parent == null -> child
        else -> parent + " " + child
    }

    private fun plain(out: MutableList<Token>, from: Int, to: Int, scope: String?) {
        if (to > from) out.add(Token(from, to, scope))
    }

    private fun emitCaptures(
        out: MutableList<Token>,
        text: String,
        match: MatchData,
        captures: Map<Int, String>,
        fallback: String?,
    ) {
        if (captures.isEmpty()) {
            plain(out, match.start, match.end, fallback)
            return
        }
        var cursor = match.start
        for ((group, range) in match.groups) {
            val name = captures[group] ?: continue
            if (range.isEmpty() || range.first < cursor) continue
            if (range.first > cursor) plain(out, cursor, range.first, fallback)
            plain(out, range.first, range.last + 1, name)
            cursor = range.last + 1
        }
        if (cursor < match.end) plain(out, cursor, match.end, fallback)
    }

    private fun advance(pos: Int, end: Int): Int = if (end > pos) end else pos + 1

    private fun merge(tokens: List<Token>): List<Token> {
        if (tokens.isEmpty()) return tokens
        val out = ArrayList<Token>(tokens.size)
        var cur = tokens[0]
        for (i in 1 until tokens.size) {
            val next = tokens[i]
            cur = if (next.scope == cur.scope && next.start == cur.end) {
                Token(cur.start, next.end, cur.scope)
            } else {
                out.add(cur)
                next
            }
        }
        out.add(cur)
        return out
    }
}
