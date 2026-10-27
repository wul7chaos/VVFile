package com.vvfile.app.core.syntax

import com.vvfile.app.core.syntax.TextMateTokenizer.Token
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarTokenizerTest {

    private fun kotlinGrammar(): Grammar {
        val file = File("../design/syntax/kotlin.tmLanguage.json")
        assertTrue("缺少 Kotlin 语法文件：${file.absolutePath}", file.exists())
        return GrammarParser.parse("source.kotlin", file.readText())
    }

    @Test
    fun `Kotlin 关键字注释字符串数字各自成形`() {
        val grammar = kotlinGrammar()
        val text = """
            package demo
            /** 文档 */
            fun main() {
                val a = "x${'$'}y" // 行尾
                val n = 42
            }
        """.trimIndent()
        val tokens = TextMateTokenizer(GrammarRegistry).tokenize(grammar, grammar.patterns, text)
        assertTrue("分词结果为空", tokens.isNotEmpty())

        fun scopeAt(snippet: String, nth: Int = 0): String? {
            var seen = 0
            val idx = text.indexOf(snippet)
            tokens.forEach {
                if (idx >= it.start && idx + snippet.length <= it.end) {
                    if (seen == nth) return it.scope
                    seen++
                }
            }
            return null
        }

        assertTrue("fun 应命中关键字：${scopeAt("fun")}", scopeAt("fun")?.contains("keyword") == true)
        assertTrue("val 应命中关键字", scopeAt("val")?.contains("keyword") == true)
        assertTrue("字符串应命中 string", scopeAt("\"x")?.contains("string") == true)
        assertTrue("42 应命中数字", scopeAt("42")?.contains("constant.numeric") == true)
        assertTrue("行尾注释应命中注释", scopeAt("// 行尾")?.contains("comment") == true)
        assertTrue("文档注释应命中注释", scopeAt("/** 文档 */")?.contains("comment") == true)
    }

    @Test
    fun `begin 未闭合时也不越界`() {
        val grammar = kotlinGrammar()
        val text = "val s = \"未闭合的字符串"
        val tokens = TextMateTokenizer(GrammarRegistry).tokenize(grammar, grammar.patterns, text)
        assertTrue(tokens.isNotEmpty())
        val last = tokens.last()
        assertTrue("token 越界：${last.end} > ${text.length}", last.end <= text.length)
    }

    @Test
    fun `分词速度在可接受范围`() {
        val grammar = kotlinGrammar()
        val one = """
            fun f(i: Int): String {
                val s = "v=${'$'}i" // note
                return if (i > 0) s else "none"
            }
        """.trimIndent()
        val text = List(200) { one }.joinToString("\n")
        val started = System.nanoTime()
        val tokens = TextMateTokenizer(GrammarRegistry).tokenize(grammar, grammar.patterns, text)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("BENCH ${text.length} chars -> ${tokens.size} tokens in ${ms}ms")
        assertTrue(tokens.isNotEmpty())
        assertTrue("${text.length} 字符耗时 ${ms}ms，太慢", ms < 1500)
    }

    @Test
    fun `语法仓库按扩展名给 scope`() {
        assertNotNull(GrammarRegistry.scopeFor("/sdcard/Main.kt"))
        assertNotNull(GrammarRegistry.scopeFor("/sdcard/App.java"))
        assertTrue(GrammarRegistry.scopeFor("/sdcard/pic.png") == null)
    }
}
