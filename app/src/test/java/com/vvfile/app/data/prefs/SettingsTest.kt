package com.vvfile.app.data.prefs

import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯逻辑单测：默认值、持久化键映射与容错解析，不依赖 DataStore/设备。 */
class SettingsTest {

    @Test
    fun `defaults match browser baseline`() {
        val settings = Settings.DEFAULT

        assertEquals(ThemeMode.SYSTEM, settings.theme)
        assertTrue(settings.dualPane)
        assertFalse(settings.showHidden)
        assertEquals(SortField.NAME, settings.sort.field)
        assertTrue(settings.sort.ascending)
        assertNull(settings.startPath)
    }

    @Test
    fun `theme keys round trip`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, Settings.themeFromKey(Settings.themeKey(mode)))
        }
        assertEquals("SYSTEM", Settings.themeKey(ThemeMode.SYSTEM))
        assertEquals("DARK", Settings.themeKey(ThemeMode.DARK))
        assertEquals("LIGHT", Settings.themeKey(ThemeMode.LIGHT))
    }

    @Test
    fun `theme key parsing tolerates case and whitespace`() {
        assertEquals(ThemeMode.LIGHT, Settings.themeFromKey(" light "))
        assertEquals(ThemeMode.DARK, Settings.themeFromKey("Dark"))
        assertEquals(ThemeMode.SYSTEM, Settings.themeFromKey(" SYSTEM\n"))
    }

    @Test
    fun `unknown theme key falls back to system`() {
        listOf(null, "", "   ", "AUTO", "DARK_BLUE").forEach { raw ->
            assertEquals("raw=$raw", ThemeMode.SYSTEM, Settings.themeFromKey(raw))
        }
    }

    @Test
    fun `sort field keys round trip`() {
        SortField.entries.forEach { field ->
            assertEquals(field, Settings.sortFieldFromKey(Settings.sortFieldKey(field)))
        }
        assertEquals("NAME", Settings.sortFieldKey(SortField.NAME))
        assertEquals("DATE", Settings.sortFieldKey(SortField.DATE))
        assertEquals("SIZE", Settings.sortFieldKey(SortField.SIZE))
        assertEquals("TYPE", Settings.sortFieldKey(SortField.TYPE))
    }

    @Test
    fun `unknown sort field falls back to name`() {
        listOf(null, "", "MODIFIED", "1").forEach { raw ->
            assertEquals("raw=$raw", SortField.NAME, Settings.sortFieldFromKey(raw))
        }
        assertEquals(SortField.DATE, Settings.sortFieldFromKey(" date "))
    }

    @Test
    fun `fromStorage applies persisted values`() {
        val settings = Settings.fromStorage(
            themeKey = "LIGHT",
            dualPane = false,
            showHidden = true,
            sortFieldKey = "SIZE",
            sortAscending = false,
            startPath = "/sdcard/Download",
        )

        assertEquals(ThemeMode.LIGHT, settings.theme)
        assertFalse(settings.dualPane)
        assertTrue(settings.showHidden)
        assertEquals(SortField.SIZE, settings.sort.field)
        assertFalse(settings.sort.ascending)
        assertEquals("/sdcard/Download", settings.startPath)
    }

    @Test
    fun `fromStorage fills defaults for missing values`() {
        val settings = Settings.fromStorage(
            themeKey = null,
            dualPane = null,
            showHidden = null,
            sortFieldKey = null,
            sortAscending = null,
            startPath = null,
        )

        assertEquals(Settings.DEFAULT, settings)
    }

    @Test
    fun `fromStorage normalizes start path`() {
        assertEquals(
            null,
            Settings.fromStorage(null, null, null, null, null, "   ").startPath,
        )
        assertEquals(
            null,
            Settings.fromStorage(null, null, null, null, null, "").startPath,
        )
        assertEquals(
            "/sdcard",
            Settings.fromStorage(null, null, null, null, null, "  /sdcard  ").startPath,
        )
    }

    @Test
    fun `normalizeStartPath trims and drops blanks`() {
        assertNull(Settings.normalizeStartPath(null))
        assertNull(Settings.normalizeStartPath(""))
        assertNull(Settings.normalizeStartPath(" \t\n"))
        assertEquals("/storage/emulated/0", Settings.normalizeStartPath(" /storage/emulated/0 "))
    }

    @Test
    fun `copy keeps unrelated fields`() {
        val original = Settings(
            theme = ThemeMode.DARK,
            dualPane = false,
            showHidden = true,
            sort = SortSpec(field = SortField.TYPE, ascending = false),
            startPath = "/sdcard",
        )

        val updated = original.copy(showHidden = false)

        assertEquals(ThemeMode.DARK, updated.theme)
        assertFalse(updated.dualPane)
        assertFalse(updated.showHidden)
        assertEquals(original.sort, updated.sort)
        assertEquals("/sdcard", updated.startPath)
    }
}
