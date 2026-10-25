package com.vvfile.app.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPreviewTest {

    @Test
    fun `扩展名决定预览类型`() {
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of("/sdcard/a.md"))
        assertEquals(PreviewKind.MARKDOWN, PreviewKind.of("/sdcard/README.MARKDOWN"))
        assertEquals(PreviewKind.HTML, PreviewKind.of("/sdcard/page.html"))
        assertEquals(PreviewKind.HTML, PreviewKind.of("/sdcard/page.htm"))
        assertEquals(PreviewKind.NONE, PreviewKind.of("/sdcard/a.txt"))
        assertEquals(PreviewKind.NONE, PreviewKind.of("/sdcard/a"))
    }

    @Test
    fun `markdown 转 html 覆盖标题 列表 代码块`() {
        val html = PreviewRenderer.markdownToHtml(
            """
            # 标题

            - 项目一
            - 项目二

            `inline` 与

            ```
            code()
            ```
            """.trimIndent(),
        )
        assertTrue(html.contains("<h1>标题</h1>"))
        assertTrue(html.contains("<li>项目一</li>"))
        assertTrue(html.contains("<code>inline</code>"))
        assertTrue(html.contains("<pre>"))
    }

    @Test
    fun `包裹层跟随深浅主题`() {
        val dark = PreviewRenderer.wrap("<p>x</p>", dark = true)
        val light = PreviewRenderer.wrap("<p>x</p>", dark = false)
        assertTrue(dark.contains("#12161E"))
        assertTrue(light.contains("#FFFFFF"))
        assertTrue(dark.contains("<p>x</p>"))
        assertTrue(light.contains("viewport"))
    }

    @Test
    fun `代码文件识别语言`() {
        assertNotNull(EditorSyntax.languageFor("/sdcard/Main.kt"))
        assertNotNull(EditorSyntax.languageFor("/sdcard/App.java"))
        assertNotNull(EditorSyntax.languageFor("/sdcard/build.sh"))
        assertNotNull(EditorSyntax.languageFor("/sdcard/data.json"))
        assertNull(EditorSyntax.languageFor("/sdcard/photo.png"))
    }

    @Test
    fun `超大文本不高亮`() {
        val huge = "val a = 1\n".repeat(EditorSyntax.MAX_HIGHLIGHT_CHARS)
        assertTrue(huge.length > EditorSyntax.MAX_HIGHLIGHT_CHARS)
        assertTrue(EditorSyntax.spans(huge, "/sdcard/Main.kt", dark = true).isEmpty())
    }

    @Test
    fun `非代码文件返回空高亮`() {
        assertTrue(EditorSyntax.spans("hello", "/sdcard/a.png", dark = true).isEmpty())
    }
}
