package com.vvfile.app.data.prefs

import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec

/** 主题模式：跟随系统 / 固定深色 / 固定浅色。 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * 用户设置。字段默认值与浏览器现状一致（双窗格、不显示隐藏文件、名称升序）。
 *
 * [startPath] 为 null 表示使用默认启动目录（内部存储根目录），由调用方决定具体路径。
 * 存储层用字符串键持久化，键映射与容错解析放在 [Companion] 里，便于纯逻辑单测。
 */
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dualPane: Boolean = true,
    val showHidden: Boolean = false,
    val sort: SortSpec = SortSpec(),
    val startPath: String? = null,
) {
    companion object {
        val DEFAULT: Settings = Settings()

        fun themeFromKey(raw: String?): ThemeMode =
            ThemeMode.entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ThemeMode.SYSTEM

        fun themeKey(theme: ThemeMode): String = theme.name

        fun sortFieldFromKey(raw: String?): SortField =
            SortField.entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: SortField.NAME

        fun sortFieldKey(field: SortField): String = field.name

        /**
         * 从持久化的原始值组装设置：缺省/非法值回落到 [DEFAULT]；
         * 起始路径做去空白与空串归一（null 表示默认目录）。
         */
        fun fromStorage(
            themeKey: String?,
            dualPane: Boolean?,
            showHidden: Boolean?,
            sortFieldKey: String?,
            sortAscending: Boolean?,
            startPath: String?,
        ): Settings = Settings(
            theme = themeFromKey(themeKey),
            dualPane = dualPane ?: DEFAULT.dualPane,
            showHidden = showHidden ?: DEFAULT.showHidden,
            sort = SortSpec(
                field = sortFieldFromKey(sortFieldKey),
                ascending = sortAscending ?: DEFAULT.sort.ascending,
            ),
            startPath = normalizeStartPath(startPath),
        )

        /** 空白路径等价于"使用默认启动目录"。 */
        fun normalizeStartPath(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
