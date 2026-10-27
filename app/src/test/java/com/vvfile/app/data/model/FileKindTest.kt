package com.vvfile.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 文件类型分类：图标显示成哪种，全在这张表上，改错一个扩展名就是错图标。 */
class FileKindTest {

    private fun kind(name: String, isDirectory: Boolean = false) =
        kindOf(FileEntry(name = name, path = "/sdcard/$name", isDirectory = isDirectory, size = 0L, lastModified = 0L))

    @Test
    fun `目录永远是文件夹`() {
        assertEquals(FileKind.Folder, kind("Download", isDirectory = true))
        // 名字里带扩展名的目录也是文件夹
        assertEquals(FileKind.Folder, kind("v1.2.zip", isDirectory = true))
    }

    @Test
    fun `常见媒体各归各位`() {
        assertEquals(FileKind.Image, kind("IMG_0001.JPG"))     // 大写扩展名
        assertEquals(FileKind.Image, kind("a.png"))
        assertEquals(FileKind.Image, kind("wx.webp"))
        assertEquals(FileKind.Image, kind("heic.heic"))
        assertEquals(FileKind.Video, kind("movie.mp4"))
        assertEquals(FileKind.Video, kind("a.mkv"))
        assertEquals(FileKind.Video, kind("c.ts"))
        assertEquals(FileKind.Audio, kind("song.flac"))
        assertEquals(FileKind.Audio, kind("voice.m4a"))
    }

    @Test
    fun `office 三种不能混`() {
        assertEquals(FileKind.Doc, kind("report.docx"))
        assertEquals(FileKind.Doc, kind("old.doc"))
        assertEquals(FileKind.Sheet, kind("data.xlsx"))
        assertEquals(FileKind.Sheet, kind("export.csv"))
        assertEquals(FileKind.Slides, kind("deck.pptx"))
        assertEquals(FileKind.Pdf, kind("manual.pdf"))
    }

    @Test
    fun `源码 配置 脚本 分开`() {
        assertEquals(FileKind.Code, kind("Main.kt"))
        assertEquals(FileKind.Code, kind("index.html"))
        assertEquals(FileKind.Code, kind("app.js"))
        assertEquals(FileKind.Config, kind("settings.json"))
        assertEquals(FileKind.Config, kind("AndroidManifest.xml"))
        assertEquals(FileKind.Config, kind("build.yaml"))
        assertEquals(FileKind.Script, kind("install.sh"))
        assertEquals(FileKind.Script, kind("run.bat"))
        assertEquals(FileKind.Text, kind("readme.md"))
        assertEquals(FileKind.Text, kind("2026.log"))
    }

    @Test
    fun `安装包 压缩包 数据库 字体`() {
        assertEquals(FileKind.Apk, kind("base.apk"))
        assertEquals(FileKind.Archive, kind("photos.zip"))
        assertEquals(FileKind.Archive, kind("backup.7z"))
        assertEquals(FileKind.Archive, kind("x.tar.gz"))       // 只看最后一段
        assertEquals(FileKind.Db, kind("notes.db"))
        assertEquals(FileKind.Db, kind("app.sqlite"))
        assertEquals(FileKind.Font, kind("SourceHanSans.ttf"))
        assertEquals(FileKind.Font, kind("font.woff2"))
    }

    @Test
    fun `认不出来的不猜`() {
        assertEquals(FileKind.Unknown, kind("noext"))
        assertEquals(FileKind.Unknown, kind("data.xyz"))
        assertEquals(FileKind.Unknown, kind(".gitignore"))      // 全是扩展名，没有主体
        assertEquals(FileKind.Unknown, kind(""))
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertEquals(kind("A.PNG"), kind("a.png"))
        assertEquals(FileKind.Pdf, kind("Manual.PDF"))
        assertEquals(FileKind.Apk, kind("APP.APK"))
    }

    @Test
    fun `缩略图集合只排除解不动的格式`() {
        val thumbable = IMAGE_EXTENSIONS - setOf("svg", "ico", "tif", "tiff")
        assert(("jpg" in thumbable))
        assert(("svg" !in thumbable))
        assert(("mp4" in VIDEO_EXTENSIONS))
    }
}
