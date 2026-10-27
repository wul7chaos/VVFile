package com.vvfile.app.data.prefs

import com.vvfile.app.core.shell.RootPolicy
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec

/** 主题模式：跟随系统 / 固定深色 / 固定浅色 / 纯黑。 */
enum class ThemeMode { SYSTEM, DARK, LIGHT, BLACK }

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
    /** 列表第二行显示权限/所有者，而不是大小/时间。 */
    val detailedList: Boolean = false,
    /** 强调色预设索引，见 AccentPresets。 */
    val accentIndex: Int = 0,
    /** 终端字号（sp）。真 PTY 之后按列排版，11sp 在 1080p 上约 43 列。 */
    val terminalFontSize: Int = 11,
    val editorFontSize: Int = 13,
    val sort: SortSpec = SortSpec(),
    val startPath: String? = null,
    /** 启动时回到上次浏览目录（startPath 优先）。 */
    val restoreLastPath: Boolean = true,
    val rootPolicy: RootPolicy = RootPolicy.AUTO,
    /** 首次启动的使用协议是否已同意；未同意时只显示协议页。 */
    val agreementAccepted: Boolean = false,
) {
    companion object {
        val DEFAULT: Settings = Settings()

        fun themeFromKey(raw: String?): ThemeMode =
            ThemeMode.entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: ThemeMode.SYSTEM

        fun themeKey(theme: ThemeMode): String = theme.name

        fun rootPolicyFromKey(raw: String?): RootPolicy =
            RootPolicy.entries.firstOrNull { it.name == raw?.trim()?.uppercase() }
                ?: RootPolicy.AUTO

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
            detailedList: Boolean? = null,
            accentIndex: Int? = null,
            terminalFontSize: Int? = null,
            editorFontSize: Int? = null,
            directoriesFirst: Boolean? = null,
            restoreLastPath: Boolean? = null,
            rootPolicyKey: String? = null,
            agreementAccepted: Boolean? = null,
        ): Settings = Settings(
            theme = themeFromKey(themeKey),
            dualPane = dualPane ?: DEFAULT.dualPane,
            showHidden = showHidden ?: DEFAULT.showHidden,
            detailedList = detailedList ?: DEFAULT.detailedList,
            accentIndex = accentIndex ?: DEFAULT.accentIndex,
            terminalFontSize = (terminalFontSize ?: DEFAULT.terminalFontSize).coerceIn(10, 24),
            editorFontSize = (editorFontSize ?: DEFAULT.editorFontSize).coerceIn(10, 24),
            sort = SortSpec(
                field = sortFieldFromKey(sortFieldKey),
                ascending = sortAscending ?: DEFAULT.sort.ascending,
                directoriesFirst = directoriesFirst ?: DEFAULT.sort.directoriesFirst,
            ),
            startPath = normalizeStartPath(startPath),
            restoreLastPath = restoreLastPath ?: DEFAULT.restoreLastPath,
            rootPolicy = rootPolicyFromKey(rootPolicyKey),
            agreementAccepted = agreementAccepted ?: DEFAULT.agreementAccepted,
        )

        /** 空白路径等价于"使用默认启动目录"。 */
        fun normalizeStartPath(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}
