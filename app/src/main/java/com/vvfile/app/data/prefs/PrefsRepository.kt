package com.vvfile.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val STORE_NAME = "vvfile_settings"

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

/**
 * 设置的唯一持久化入口（DataStore Preferences）。
 *
 * - [settings] 是冷流，每次订阅读盘一次；损坏/读取 IO 失败时回落到空偏好（即默认设置）。
 * - 所有写入走 [DataStore.edit]，读-改-写在同一个事务里完成，并发 setter 不会互相覆盖。
 * - [get] 提供进程内单例；Context 只取 applicationContext，避免持有 Activity。
 */
class PrefsRepository private constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<Settings> = dataStore.data
        .catch { failure ->
            // 文件损坏或读盘失败时用默认值兜底，避免设置页与浏览器启动即崩
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { it.toSettings() }
        .distinctUntilChanged()

    /** 读当前值、应用 [transform]、原子写回。 */
    suspend fun update(transform: (Settings) -> Settings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences.write(next)
        }
    }

    suspend fun setTheme(mode: ThemeMode) = update { it.copy(theme = mode) }

    suspend fun setDualPane(enabled: Boolean) = update { it.copy(dualPane = enabled) }

    suspend fun setShowHidden(enabled: Boolean) = update { it.copy(showHidden = enabled) }

    suspend fun setDetailedList(enabled: Boolean) = update { it.copy(detailedList = enabled) }

    suspend fun setAccentIndex(index: Int) = update { it.copy(accentIndex = index.coerceAtLeast(0)) }

    suspend fun setTerminalFontSize(size: Int) = update {
        it.copy(terminalFontSize = size.coerceIn(10, 24))
    }

    suspend fun setEditorFontSize(size: Int) = update {
        it.copy(editorFontSize = size.coerceIn(10, 24))
    }

    suspend fun setSort(spec: SortSpec) = update { it.copy(sort = spec) }

    suspend fun setSortField(field: SortField) = update { current ->
        current.copy(sort = current.sort.copy(field = field))
    }

    suspend fun setSortAscending(ascending: Boolean) = update { current ->
        current.copy(sort = current.sort.copy(ascending = ascending))
    }

    suspend fun setStartPath(path: String?) = update {
        it.copy(startPath = Settings.normalizeStartPath(path))
    }

    companion object {
        @Volatile
        private var instance: PrefsRepository? = null

        /** 进程内单例；ServiceLocator/Application 两处调用都拿到同一实例。 */
        fun get(context: Context): PrefsRepository =
            instance ?: synchronized(this) {
                instance ?: PrefsRepository(context.applicationContext.settingsDataStore)
                    .also { instance = it }
            }
    }
}

private object PrefKeys {
    val THEME = stringPreferencesKey("theme")
    val DUAL_PANE = booleanPreferencesKey("dual_pane")
    val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
    val DETAILED_LIST = booleanPreferencesKey("detailed_list")
    val ACCENT_INDEX = intPreferencesKey("accent_index")
    val TERMINAL_FONT = intPreferencesKey("terminal_font_size")
    val EDITOR_FONT = intPreferencesKey("editor_font_size")
    val SORT_FIELD = stringPreferencesKey("sort_field")
    val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
    val START_PATH = stringPreferencesKey("start_path")
}

private fun Preferences.toSettings(): Settings = Settings.fromStorage(
    themeKey = this[PrefKeys.THEME],
    dualPane = this[PrefKeys.DUAL_PANE],
    showHidden = this[PrefKeys.SHOW_HIDDEN],
    detailedList = this[PrefKeys.DETAILED_LIST],
    accentIndex = this[PrefKeys.ACCENT_INDEX],
    terminalFontSize = this[PrefKeys.TERMINAL_FONT],
    editorFontSize = this[PrefKeys.EDITOR_FONT],
    sortFieldKey = this[PrefKeys.SORT_FIELD],
    sortAscending = this[PrefKeys.SORT_ASCENDING],
    startPath = this[PrefKeys.START_PATH],
)

private fun MutablePreferences.write(settings: Settings) {
    this[PrefKeys.THEME] = Settings.themeKey(settings.theme)
    this[PrefKeys.DUAL_PANE] = settings.dualPane
    this[PrefKeys.SHOW_HIDDEN] = settings.showHidden
    this[PrefKeys.DETAILED_LIST] = settings.detailedList
    this[PrefKeys.ACCENT_INDEX] = settings.accentIndex
    this[PrefKeys.TERMINAL_FONT] = settings.terminalFontSize
    this[PrefKeys.EDITOR_FONT] = settings.editorFontSize
    this[PrefKeys.SORT_FIELD] = Settings.sortFieldKey(settings.sort.field)
    this[PrefKeys.SORT_ASCENDING] = settings.sort.ascending
    val startPath = Settings.normalizeStartPath(settings.startPath)
    if (startPath == null) {
        remove(PrefKeys.START_PATH)
    } else {
        this[PrefKeys.START_PATH] = startPath
    }
}

