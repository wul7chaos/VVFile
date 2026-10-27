package com.vvfile.app.data.model

/**
 * 文件类型分类，决定列表里显示哪张图标。
 *
 * 图标是本地 Qwen-Image 生成的一套位图（`design/icons`），
 * 分类粒度按「一眼能认出来」来定：office 三种各自独立，源码/配置/脚本分开，
 * 数据库和字体也单独给图标，剩下的一律 Unknown。
 */
enum class FileKind {
    Folder,
    Image,
    Video,
    Audio,
    Apk,
    Archive,
    Text,
    Pdf,
    Doc,
    Sheet,
    Slides,
    Code,
    Config,
    Script,
    Db,
    Font,
    Unknown,
}

val IMAGE_EXTENSIONS: Set<String> = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "avif", "ico", "tif", "tiff",
)

val VIDEO_EXTENSIONS: Set<String> = setOf(
    "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m2ts", "wmv", "mpg", "mpeg", "rmvb",
)

val AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "amr", "wma", "mid", "midi", "ape",
)

val ARCHIVE_EXTENSIONS: Set<String> = setOf(
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "jar", "war", "iso", "cab", "lz", "lzma",
)

private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "log", "nfo", "readme", "rtf")
private val DOC_EXTENSIONS = setOf("doc", "docx", "odt", "wps", "pages", "epub")
private val SHEET_EXTENSIONS = setOf("xls", "xlsx", "csv", "ods", "numbers")
private val SLIDES_EXTENSIONS = setOf("ppt", "pptx", "odp", "key")
private val CODE_EXTENSIONS = setOf(
    "html", "htm", "css", "scss", "less", "js", "mjs", "cjs", "ts", "tsx", "jsx",
    "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc",
    "cs", "php", "swift", "lua", "pl", "dart", "vue", "gradle", "mk",
)
private val CONFIG_EXTENSIONS = setOf(
    "json", "xml", "yaml", "yml", "ini", "conf", "cfg", "properties", "toml", "env", "plist",
)
private val SCRIPT_EXTENSIONS = setOf("sh", "bash", "zsh", "bat", "cmd", "ps1")
private val DB_EXTENSIONS = setOf("db", "db3", "sqlite", "sql", "mdb", "realm")
private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "woff", "woff2")

/** 判断顺序有讲究：先具体后笼统，比如 `.js` 归源码、`.sh` 归脚本。 */
fun kindOf(isDirectory: Boolean, extension: String): FileKind = when {
    isDirectory -> FileKind.Folder
    extension in IMAGE_EXTENSIONS -> FileKind.Image
    extension in VIDEO_EXTENSIONS -> FileKind.Video
    extension in AUDIO_EXTENSIONS -> FileKind.Audio
    extension == "apk" -> FileKind.Apk
    extension == "pdf" -> FileKind.Pdf
    extension in DOC_EXTENSIONS -> FileKind.Doc
    extension in SHEET_EXTENSIONS -> FileKind.Sheet
    extension in SLIDES_EXTENSIONS -> FileKind.Slides
    extension in ARCHIVE_EXTENSIONS -> FileKind.Archive
    extension in DB_EXTENSIONS -> FileKind.Db
    extension in FONT_EXTENSIONS -> FileKind.Font
    extension in SCRIPT_EXTENSIONS -> FileKind.Script
    extension in CONFIG_EXTENSIONS -> FileKind.Config
    extension in CODE_EXTENSIONS -> FileKind.Code
    extension in TEXT_EXTENSIONS -> FileKind.Text
    else -> FileKind.Unknown
}

fun kindOf(entry: FileEntry): FileKind = kindOf(entry.isDirectory, entry.extension)
