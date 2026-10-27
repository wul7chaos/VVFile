package com.vvfile.app.core.syntax

import android.content.Context

/**
 * 语法仓库：从 `assets/syntax/grammars/` 读 VS Code 的 TextMate 语法。
 *
 * 扩展名映射在 `SyntaxManifest`（构建期由 `design/syntax/pull.py` 生成），
 * 不依赖 Context，所以单测里也能判断「这个文件该不该着色」；
 * 真正解析语法时才需要 `init(context)`，语法文件按需加载并缓存。
 */
object GrammarRegistry {

    private const val DIR = "syntax/grammars"

    private var appContext: Context? = null
    private val grammars = HashMap<String, Grammar>()
    private val failed = HashSet<String>()

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        grammars.clear()
        failed.clear()
    }

    /** 路径 → scopeName；不认识的扩展名返回 null（编辑器就不着色）。 */
    fun scopeFor(path: String): String? {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return SyntaxManifest.extensions[ext]
    }

    /** 按 scopeName 取语法；加载失败（文件缺失、JSON 坏）返回 null 并记住，不反复重试。 */
    @Synchronized
    fun grammar(scopeName: String): Grammar? {
        grammars[scopeName]?.let { return it }
        if (scopeName in failed) return null
        val file = SyntaxManifest.files[scopeName]
        val ctx = appContext
        if (file == null || ctx == null) {
            failed += scopeName
            return null
        }
        return runCatching {
            val json = ctx.assets.open("$DIR/$file").bufferedReader().use { it.readText() }
            GrammarParser.parse(scopeName, json).also { grammars[scopeName] = it }
        }.getOrElse {
            failed += scopeName
            null
        }
    }
}
