package com.vvfile.app.core.format

/** 常见扩展名到 MIME 的映射；未知类型回退 application/octet-stream。 */
object MimeTypes {

    private val map = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "json" to "application/json",
        "xml" to "application/xml",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "log" to "text/plain",
        "ini" to "text/plain",
        "conf" to "text/plain",
        "properties" to "text/plain",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "kt" to "text/plain",
        "java" to "text/plain",
        "py" to "text/x-python",
        "sh" to "text/x-shellscript",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "ico" to "image/x-icon",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "apk" to "application/vnd.android.package-archive",
        "pdf" to "application/pdf",
    )

    fun forPath(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return DEFAULT
        return map[name.substring(dot + 1).lowercase()] ?: DEFAULT
    }

    fun isText(path: String): Boolean = forPath(path).startsWith("text/") ||
        forPath(path) == "application/json" ||
        forPath(path) == "application/xml" ||
        forPath(path) == "application/javascript"

    const val DEFAULT = "application/octet-stream"
}
