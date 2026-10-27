"""从本机 VS Code 源码里捞出 TextMate 语法，放进 assets/syntax/。

只拷需要的语言，生成 manifest.json（扩展名 → scopeName），App 端按 scopeName 加载，
遇到 grammar 里 include 的外部 scope 再按需加载被引用的那几份。

用法：
    python design/syntax/pull.py                 # 默认 <仓库>/vscode
    python design/syntax/pull.py D:\\Tools\\vscode
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "syntax")
# 语法文件是从 VS Code 源码里抄的，走 MIT；仓库里不放它的完整 checkout
VSCODE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "vscode")

# scopeName -> 相对 VS Code 仓库的路径
GRAMMARS = {
    "source.java": "extensions/java/syntaxes/java.tmLanguage.json",
    "source.js": "extensions/javascript/syntaxes/JavaScript.tmLanguage.json",
    "source.ts": "extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json",
    "source.c": "extensions/cpp/syntaxes/c.tmLanguage.json",
    "source.cs": "extensions/csharp/syntaxes/csharp.tmLanguage.json",
    "source.python": "extensions/python/syntaxes/MagicPython.tmLanguage.json",
    "source.ruby": "extensions/ruby/syntaxes/ruby.tmLanguage.json",
    "source.perl": "extensions/perl/syntaxes/perl.tmLanguage.json",
    "source.php": "extensions/php/syntaxes/php.tmLanguage.json",
    "source.go": "extensions/go/syntaxes/go.tmLanguage.json",
    "source.rust": "extensions/rust/syntaxes/rust.tmLanguage.json",
    "source.swift": "extensions/swift/syntaxes/swift.tmLanguage.json",
    "source.dart": "extensions/dart/syntaxes/dart.tmLanguage.json",
    "source.shell": "extensions/shellscript/syntaxes/shell-unix-bash.tmLanguage.json",
    "source.json": "extensions/json/syntaxes/JSON.tmLanguage.json",
    "source.json.comments": "extensions/json/syntaxes/JSONC.tmLanguage.json",
    "text.xml": "extensions/xml/syntaxes/xml.tmLanguage.json",
    "text.html.basic": "extensions/html/syntaxes/html.tmLanguage.json",
    "source.css": "extensions/css/syntaxes/css.tmLanguage.json",
    "source.css.scss": "extensions/scss/syntaxes/scss.tmLanguage.json",
    "source.css.less": "extensions/less/syntaxes/less.tmLanguage.json",
    "text.html.markdown": "extensions/markdown-basics/syntaxes/markdown.tmLanguage.json",
    "source.yaml": "extensions/yaml/syntaxes/yaml-1.2.tmLanguage.json",
    "source.ini": "extensions/ini/syntaxes/ini.tmLanguage.json",
    "source.groovy": "extensions/groovy/syntaxes/groovy.tmLanguage.json",
    "source.batchfile": "extensions/bat/syntaxes/batchfile.tmLanguage.json",
    "source.powershell": "extensions/powershell/syntaxes/powershell.tmLanguage.json",
    "source.lua": "extensions/lua/syntaxes/lua.tmLanguage.json",
    "source.sql": "extensions/sql/syntaxes/sql.tmLanguage.json",
    "source.clojure": "extensions/clojure/syntaxes/clojure.tmLanguage.json",
    "source.coffee": "extensions/coffeescript/syntaxes/coffeescript.tmLanguage.json",
    "source.fsharp": "extensions/fsharp/syntaxes/fsharp.tmLanguage.json",
    "source.julia": "extensions/julia/syntaxes/julia.tmLanguage.json",
    "source.r": "extensions/r/syntaxes/r.tmLanguage.json",
    "source.makefile": "extensions/make/syntaxes/make.tmLanguage.json",
    "source.diff": "extensions/diff/syntaxes/diff.tmLanguage.json",
    "source.dockerfile": "extensions/docker/syntaxes/docker.tmLanguage.json",
    "text.log": "extensions/log/syntaxes/log.tmLanguage.json",
}

# 我们认识的扩展名 -> scopeName（source.kotlin 是自己写的，见 kotlin.tmLanguage.json）
EXTENSIONS = {
    "kt": "source.kotlin", "kts": "source.kotlin",
    "java": "source.java",
    "js": "source.js", "mjs": "source.js", "cjs": "source.js", "jsx": "source.js",
    "ts": "source.ts", "mts": "source.ts", "cts": "source.ts", "tsx": "source.ts",
    "c": "source.c", "h": "source.c",
    # C++ 用 C 的语法：cpp 那份 682 KB 的语法依赖一堆 Java 正则支持不了的写法，
    # 实测一半文本不上色，C 的规则反而干净
    "cpp": "source.c", "cc": "source.c", "cxx": "source.c",
    "hpp": "source.c", "hxx": "source.c", "ino": "source.c",
    "cs": "source.cs",
    "py": "source.python",
    "rb": "source.ruby",
    "pl": "source.perl", "pm": "source.perl",
    "php": "source.php",
    "go": "source.go",
    "rs": "source.rust",
    "swift": "source.swift",
    "dart": "source.dart",
    "sh": "source.shell", "bash": "source.shell", "zsh": "source.shell",
    "fish": "source.shell",
    "json": "source.json",
    "json5": "source.json.comments", "jsonc": "source.json.comments",
    "xml": "text.xml", "svg": "text.xml", "plist": "text.xml",
    "html": "text.html.basic", "htm": "text.html.basic",
    "xhtml": "text.html.basic", "vue": "text.html.basic",
    "css": "source.css",
    "scss": "source.css.scss",
    "less": "source.css.less",
    "md": "text.html.markdown", "markdown": "text.html.markdown",
    "yaml": "source.yaml", "yml": "source.yaml",
    "ini": "source.ini", "cfg": "source.ini", "conf": "source.ini",
    "properties": "source.ini",
    "gradle": "source.groovy", "groovy": "source.groovy",
    "bat": "source.batchfile", "cmd": "source.batchfile",
    "ps1": "source.powershell",
    "lua": "source.lua",
    "sql": "source.sql",
    "clj": "source.clojure", "cljs": "source.clojure",
    "coffee": "source.coffee",
    "fs": "source.fsharp",
    "jl": "source.julia",
    "r": "source.r",
    "mk": "source.makefile",
    "log": "text.log",
}


def main():
    dst = os.path.join(OUT, "grammars")
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(dst, exist_ok=True)
    written = {}
    missing = []
    for scope, rel in GRAMMARS.items():
        src = os.path.join(VSCODE, rel)
        if not os.path.exists(src):
            missing.append(rel)
            continue
        name = scope.replace(".", "_") + ".json"
        shutil.copyfile(src, os.path.join(dst, name))
        written[scope] = name

    # 自己写的 Kotlin 语法
    kotlin = os.path.join(HERE, "kotlin.tmLanguage.json")
    if os.path.exists(kotlin):
        shutil.copyfile(kotlin, os.path.join(dst, "source_kotlin.json"))
        written["source.kotlin"] = "source_kotlin.json"

    manifest = {
        "comment": "语法文件抄自 microsoft/vscode（MIT），scopeName -> grammars/ 下的文件名",
        "grammars": written,
        "extensions": {k: v for k, v in EXTENSIONS.items() if v in written},
    }
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1, sort_keys=True)

    # 同时生成 Kotlin 清单：扩名映射不依赖 Context，单测里也能用
    kt = os.path.join(ROOT, "app", "src", "main", "java", "com", "vvfile", "app", "core",
                      "syntax", "SyntaxManifest.kt")
    with open(kt, "w", encoding="utf-8", newline="\n") as f:
        f.write("package com.vvfile.app.core.syntax\n\n")
        f.write("// 由 design/syntax/pull.py 生成，改语言请改脚本后重跑，别手改这个文件。\n")
        f.write("internal object SyntaxManifest {\n")
        f.write("    /** 扩展名 -> TextMate scopeName */\n")
        f.write("    val extensions: Map<String, String> = mapOf(\n")
        for ext, scope in sorted(manifest["extensions"].items()):
            f.write('        "%s" to "%s",\n' % (ext, scope))
        f.write("    )\n\n")
        f.write("    /** scopeName -> assets/syntax/grammars/ 下的文件名 */\n")
        f.write("    val files: Map<String, String> = mapOf(\n")
        for scope, name in sorted(written.items()):
            f.write('        "%s" to "%s",\n' % (scope, name))
        f.write("    )\n}\n")

    total = sum(os.path.getsize(os.path.join(dst, n)) for n in written.values())
    print("grammars: %d, %.1f MB -> %s" % (len(written), total / 1024 / 1024, dst))
    print("manifest -> %s" % kt)
    if missing:
        print("missing (%d): %s" % (len(missing), ", ".join(missing)))


if __name__ == "__main__":
    main()
