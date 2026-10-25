package com.vvfile.app.feature.apps

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vvfile.app.R
import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.data.app.AppInfo
import com.vvfile.app.data.app.AppSortField
import com.vvfile.app.data.app.AppTab

/**
 * 「已安装应用」：布局对齐 MT —— 顶部返回 / 标题 / 搜索 / 更多，
 * 下面「用户应用 / 系统应用」两个页签，列表每项三行（名称、版本+大小、包名）。
 *
 * 列表数据带进程级缓存：再次打开秒开，只有包变化（安装/卸载/更新广播、
 * `getChangedPackages` 报告有变化）或下拉刷新时才重新扫描。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppListScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPath: ((String) -> Unit)? = null,
    viewModel: AppListViewModel = viewModel(factory = AppListViewModel.factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionTarget by remember { mutableStateOf<AppInfo?>(null) }
    var sortOpen by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    LaunchedEffect(Unit) {
        viewModel.onVisible()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        if (state.selectionMode) {
            SelectionTopBar(
                count = state.selected.size,
                onClose = viewModel::clearSelection,
                onSelectAll = viewModel::selectAllVisible,
            )
        } else if (state.searchOpen) {
            SearchTopBar(
                query = state.query,
                onQueryChange = viewModel::setQuery,
                onBack = viewModel::closeSearch,
                onSort = { sortOpen = true },
                onSelectAll = viewModel::selectAllVisible,
            )
        } else {
            AppTopBar(
                userCount = state.userCount,
                systemCount = state.systemCount,
                fromCache = state.fromCache,
                onBack = onClose,
                onSearch = viewModel::toggleSearch,
                onSort = { sortOpen = true },
                onSelectAll = viewModel::selectAllVisible,
            )
        }

        if (!state.selectionMode && !state.searchOpen) {
            AppTabs(
                tab = state.tab,
                userCount = state.userCount,
                systemCount = state.systemCount,
                onSelect = viewModel::setTab,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }

        Box(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && state.apps.isEmpty() -> LoadingState()
                    state.error != null && state.apps.isEmpty() -> ErrorState(state.error!!) {
                        viewModel.refresh()
                    }
                    state.visibleApps.isEmpty() -> EmptyState(
                        noAppsAtAll = state.apps.isEmpty(),
                        tab = state.tab,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 2.dp, bottom = 8.dp),
                    ) {
                        items(state.visibleApps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                selectionMode = state.selectionMode,
                                selected = app.packageName in state.selected,
                                onClick = {
                                    if (state.selectionMode) {
                                        viewModel.toggleSelect(app.packageName)
                                    } else {
                                        actionTarget = app
                                    }
                                },
                                onLongClick = { viewModel.enterSelection(app.packageName) },
                            )
                        }
                    }
                }
            }
        }

        if (state.selectionMode) {
            SelectionBottomBar(
                count = state.selected.size,
                onSelectAll = viewModel::selectAllVisible,
                onExport = viewModel::exportSelected,
                onCancel = viewModel::clearSelection,
            )
        }
    }

    if (sortOpen) {
        SortDialog(
            field = state.sortField,
            descending = state.sortDescending,
            onConfirm = { field, descending ->
                viewModel.setSort(field, descending)
                sortOpen = false
            },
            onDismiss = { sortOpen = false },
        )
    }

    actionTarget?.let { app ->
        AppActionSheet(
            app = app,
            onDismiss = { actionTarget = null },
            onLaunch = {
                actionTarget = null
                viewModel.launch(app)
            },
            onInfo = {
                actionTarget = null
                viewModel.openAppInfo(app)
            },
            onExport = {
                actionTarget = null
                viewModel.export(app)
            },
            onUninstall = {
                actionTarget = null
                viewModel.requestUninstall(app)
            },
        )
    }

    state.details?.let { details ->
        AppDetailsDialog(
            details = details,
            onDismiss = viewModel::dismissDetails,
            onMore = viewModel::openSystemAppInfo,
            onExtract = {
                val app = state.detailsApp
                viewModel.dismissDetails()
                if (app != null) viewModel.export(app)
            },
            onOpenPath = { path ->
                viewModel.dismissDetails()
                onOpenPath?.invoke(path)
            },
        )
    }

    val exportingPackage = state.exportingPackage
    if (exportingPackage != null) {
        val app = state.apps.firstOrNull { it.packageName == exportingPackage }
        ExportDialog(
            label = app?.label ?: exportingPackage,
            progress = state.exportProgress,
            index = state.exportingIndex,
            total = state.exportingTotal,
        )
    }
}

@Composable
private fun AppTopBar(
    userCount: Int,
    systemCount: Int,
    fromCache: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSort: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.apps_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.apps_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (fromCache) {
                    "用户 $userCount · 系统 $systemCount · 已缓存"
                } else {
                    "用户 $userCount · 系统 $systemCount"
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSearch) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.apps_search_hint),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MoreMenu(onSort = onSort, onSelectAll = onSelectAll)
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSort: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "退出搜索",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.apps_search_hint),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "清空",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        MoreMenu(onSort = onSort, onSelectAll = onSelectAll)
    }
}
/** 顶部「⋮」菜单：排序方式 / 全选（按需求不做「设置」）。 */
@Composable
private fun MoreMenu(
    onSort: () -> Unit,
    onSelectAll: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("排序方式") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
                onClick = {
                    open = false
                    onSort()
                },
            )
            DropdownMenuItem(
                text = { Text("全选") },
                leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                onClick = {
                    open = false
                    onSelectAll()
                },
            )
        }
    }
}


@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "取消选择",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.ui_selection_title, count),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        TextButton(onClick = onSelectAll) {
            Icon(
                imageVector = Icons.Outlined.SelectAll,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ui_selection_all), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun AppTabs(
    tab: AppTab,
    userCount: Int,
    systemCount: Int,
    onSelect: (AppTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        AppTabItem(
            label = "用户应用",
            count = userCount,
            selected = tab == AppTab.USER,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(AppTab.USER) },
        )
        AppTabItem(
            label = "系统应用",
            count = systemCount,
            selected = tab == AppTab.SYSTEM,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(AppTab.SYSTEM) },
        )
    }
}

@Composable
private fun AppTabItem(
    label: String,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 0) "$label $count" else label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                ),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            )
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = null)
            Spacer(Modifier.width(4.dp))
        }
        AppIcon(app, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.versionName.ifBlank { app.versionCode.toString() },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = SizeFormatter.formatCompact(app.apkSizeBytes),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionBottomBar(
    count: Int,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(54.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarAction(Icons.Outlined.SelectAll, "全选", Modifier.weight(1f), onSelectAll)
        BarAction(
            Icons.Outlined.Download,
            if (count > 0) "导出 $count" else "导出",
            Modifier.weight(1f),
            onExport,
            enabled = count > 0,
        )
        BarAction(Icons.Outlined.Close, "取消", Modifier.weight(1f), onCancel)
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = tint)
    }
}

/** MT 的排序面板：单选排序字段 + 逆向排序。 */
@Composable
private fun SortDialog(
    field: AppSortField,
    descending: Boolean,
    onConfirm: (AppSortField, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(field) }
    var reversed by remember { mutableStateOf(descending) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column {
                Text(
                    text = "排序方式",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SortOption("应用名称", picked == AppSortField.NAME) { picked = AppSortField.NAME }
                    SortOption("应用包名", picked == AppSortField.PACKAGE) { picked = AppSortField.PACKAGE }
                    SortOption("安装包大小", picked == AppSortField.SIZE) { picked = AppSortField.SIZE }
                    SortOption("首次安装时间", picked == AppSortField.FIRST_INSTALL) {
                        picked = AppSortField.FIRST_INSTALL
                    }
                    SortOption("最后更新时间", picked == AppSortField.LAST_UPDATE) {
                        picked = AppSortField.LAST_UPDATE
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { reversed = !reversed }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = reversed, onCheckedChange = { reversed = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "逆向排序",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(picked, reversed) }) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun SortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 点击应用后展开的功能面板（替代行尾三点菜单）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppActionSheet(
    app: AppInfo,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onUninstall: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${app.packageName} · ${app.versionName} · ${SizeFormatter.format(app.apkSizeBytes)}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SheetAction(Icons.Outlined.PlayArrow, stringResource(R.string.apps_launch), onLaunch)
            SheetAction(Icons.Outlined.Info, stringResource(R.string.apps_info), onInfo)
            SheetAction(Icons.Outlined.FileDownload, stringResource(R.string.apps_export), onExport)
            SheetAction(
                icon = Icons.Outlined.DeleteOutline,
                label = stringResource(R.string.apps_uninstall),
                onClick = onUninstall,
                enabled = !app.isSystem,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
    }
}

@Composable
private fun AppIcon(app: AppInfo, size: androidx.compose.ui.unit.Dp) {
    val icon = app.icon
    if (icon != null) {
        androidx.compose.foundation.Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size)
                .clip(RectangleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.apps_loading),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(noAppsAtAll: Boolean, tab: AppTab) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when {
                noAppsAtAll -> stringResource(R.string.apps_empty)
                tab == AppTab.USER -> "没有匹配的用户应用"
                else -> "没有匹配的系统应用"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.apps_error),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.apps_retry))
        }
    }
}

@Composable
private fun ExportDialog(label: String, progress: Float, index: Int = 1, total: Int = 1) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = if (total > 1) "正在导出（$index/$total）" else stringResource(R.string.apps_exporting),
            )
        },
        text = {
            Column {
                Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.apps_export_cancel_hint),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.apps_export_progress, percent),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

