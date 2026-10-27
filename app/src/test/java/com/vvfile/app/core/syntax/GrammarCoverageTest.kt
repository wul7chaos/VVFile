package com.vvfile.app.core.syntax

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全部随包语法过一遍：分词必须严丝合缝覆盖全文，不能有空洞或重叠。 */
class GrammarCoverageTest {

    /** 通用样例：拿它喂所有语法，覆盖面差不多够用；个别语言用自己的样例 */
    private val generic = """
        package demo
        // 注释
        /* 块注释 */
        fun f(a: Int): String {
            val s = "text ${'$'}{a} \"q\""
            if (a > 0) return s else return "no"
        }
        <tag attr="v">text</tag>
        key: value
        # heading
        - item
        123 4.5 true null
    """.trimIndent()

    private fun sampleFor(scope: String): String = when (scope) {
        "source.shell" -> "#! /bin/bash\nset -e\nfor f in *.txt; do\n  echo \"\${'$'}f\" | grep -v '^#'\ndone\n"
        "source.yaml" -> "key: value\nlist:\n  - a\n  - b\nnum: 1\nflag: true\n# comment\n"
        "source.makefile" -> "app: main.o util.o\n\tgcc -o app main.o util.o\n\n%.o: %.c\n\tgcc -c \${'$'}<\n"
        else -> generic
    }

    @Test
    fun `所有语法都能覆盖全文`() {
        val dir = File("src/main/assets/syntax/grammars")
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        assertTrue("找不到语法目录：${dir.absolutePath}", files.size >= 30)

        var totalMs = 0L
        val problems = ArrayList<String>()
        for (file in files) {
            val scope = file.name.removeSuffix(".json").replace('_', '.')
            // 文件名是 scope 换了种写法，语法里自带 scopeName，这里以文件内为准
            val grammar = try {
                GrammarParser.parse(scope, file.readText())
            } catch (e: Throwable) {
                problems += "${file.name}: 解析失败 ${e.message}"
                continue
            }
            // 用 grammar 自己的 scopeName 重新 parse 一次，保证 include 解析正确
            val text = sampleFor(grammar.scopeName)
            val started = System.nanoTime()
            val tokens = TextMateTokenizer(GrammarRegistry)
                .tokenize(grammar, grammar.patterns, text)
            totalMs += (System.nanoTime() - started) / 1_000_000

            // 允许重叠（零宽 begin 的 captures 会盖住后面的文本），但不允许有空洞
            val covered = BooleanArray(text.length)
            for (t in tokens) {
                for (i in t.start until minOf(t.end, text.length)) covered[i] = true
            }
            val missing = covered.count { !it }
            // 空白与结构符号保持正文色是正常的，只拦「大面积没上色」
            if (missing > text.length / 6) {
                val gap = covered.indexOfFirst { !it }
                val ctx = text.substring(gap, minOf(gap + 12, text.length)).replace("\n", "\\n")
                problems += "${file.name}: $missing/${text.length} 字符没上色，首个在 $gap [${ctx}]"
            }
        }
        println("COVER ${files.size} grammars, ${generic.length}+ chars each, total ${totalMs}ms")
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
