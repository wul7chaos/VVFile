package com.vvfile.app.feature.settings
import androidx.compose.ui.graphics.RectangleShape

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vvfile.app.BuildConfig
import com.vvfile.app.R
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.data.prefs.Settings
import com.vvfile.app.data.prefs.ThemeMode
import com.vvfile.app.ui.theme.VVFileTheme

/**
 * 独立设置页。不直接改主题：主题由 Lead 在 App 根部读取 [Settings.theme] 后传给
 * `VVFileTheme(darkTheme = ...)`；本页只负责写设置。
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearError()
    }

    BackHandler { onClose() }

    SettingsContent(
        settings = settings,
        onClose = onClose,
        onThemeChange = viewModel::setTheme,
        onDualPaneChange = viewModel::setDualPane,
        onShowHiddenChange = viewModel::setShowHidden,
        onSortFieldChange = viewModel::setSortField,
        onSortAscendingChange = viewModel::setSortAscending,
        modifier = modifier,
    )
}

/** 无状态内容层，便于预览与后续复用。 */
@Composable
internal fun SettingsContent(
    settings: Settings,
    onClose: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onDualPaneChange: (Boolean) -> Unit,
    onShowHiddenChange: (Boolean) -> Unit,
    onSortFieldChange: (SortField) -> Unit,
    onSortAscendingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(onClose = onClose)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsSection(
                title = stringResource(R.string.settings_group_appearance),
                icon = Icons.Outlined.Palette,
            ) {
                ThemeSetting(theme = settings.theme, onThemeChange = onThemeChange)
            }

            SettingsSection(
                title = stringResource(R.string.settings_group_browsing),
                icon = Icons.Outlined.FolderOpen,
            ) {
                SettingSwitchRow(
                    title = stringResource(R.string.settings_dual_pane),
                    subtitle = stringResource(R.string.settings_dual_pane_hint),
                    checked = settings.dualPane,
                    onCheckedChange = onDualPaneChange,
                )
                RowDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_show_hidden),
                    subtitle = stringResource(R.string.settings_show_hidden_hint),
                    checked = settings.showHidden,
                    onCheckedChange = onShowHiddenChange,
                )
                RowDivider()
                SortFieldSetting(field = settings.sort.field, onSortFieldChange = onSortFieldChange)
                RowDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.settings_sort_ascending),
                    subtitle = stringResource(R.string.settings_sort_ascending_hint),
                    checked = settings.sort.ascending,
                    onCheckedChange = onSortAscendingChange,
                )
            }

            SettingsSection(
                title = stringResource(R.string.settings_group_about),
                icon = Icons.Outlined.Info,
            ) {
                AboutRows()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.settings_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .background(MaterialTheme.colorScheme.surface),
            content = content,
        )
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        RectSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 项目全局直角风格：不用 Material 的胶囊开关。 */
@Composable
private fun RectSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val track = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val thumb = if (checked) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .background(track)
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(thumb),
        )
    }
}

@Composable
private fun ThemeSetting(theme: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_theme_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == theme,
                    onClick = { onThemeChange(mode) },
                    label = { Text(themeLabel(mode), style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = checkIcon(selected = mode == theme),
                )
            }
        }
    }
}

@Composable
private fun SortFieldSetting(field: SortField, onSortFieldChange: (SortField) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.settings_default_sort),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_default_sort_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortField.entries.forEach { candidate ->
                FilterChip(
                    selected = candidate == field,
                    onClick = { onSortFieldChange(candidate) },
                    label = { Text(sortLabel(candidate), style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = checkIcon(selected = candidate == field),
                )
            }
        }
    }
}

@Composable
private fun AboutRows() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_about_name),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_about_desc),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    RowDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_about_version),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun checkIcon(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        null
    }

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
}

@Composable
private fun sortLabel(field: SortField): String = when (field) {
    SortField.NAME -> stringResource(R.string.settings_sort_name)
    SortField.DATE -> stringResource(R.string.settings_sort_date)
    SortField.SIZE -> stringResource(R.string.settings_sort_size)
    SortField.TYPE -> stringResource(R.string.settings_sort_type)
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1218)
@Composable
private fun SettingsContentPreview() {
    VVFileTheme {
        SettingsContent(
            settings = Settings(
                theme = ThemeMode.DARK,
                dualPane = false,
                showHidden = true,
                sort = SortSpec(field = SortField.SIZE, ascending = false),
            ),
            onClose = {},
            onThemeChange = {},
            onDualPaneChange = {},
            onShowHiddenChange = {},
            onSortFieldChange = {},
            onSortAscendingChange = {},
        )
    }
}


