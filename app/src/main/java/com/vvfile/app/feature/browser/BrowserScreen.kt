package com.vvfile.app.feature.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vvfile.app.R
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.ui.components.PathBar
import com.vvfile.app.ui.theme.VVDanger
import com.vvfile.app.ui.theme.VVWarn
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(viewModel: BrowserViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(hasStoragePermission(context)) }
    var showAbout by remember { mutableStateOf(false) }

    // 每个窗格一份滚动状态；路径变化时回到顶部（requestScrollToItem 在列表尚未布局时也生效）
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    LaunchedEffect(state.left.path) { leftListState.requestScrollToItem(0) }
    LaunchedEffect(state.right.path) { rightListState.requestScrollToItem(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = hasStoragePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val activePane = if (state.active == PaneId.LEFT) state.left else state.right
    val selectionActive = activePane.selectionMode
    val notImplemented: () -> Unit = {
        Toast.makeText(context, R.string.ui_action_m2_placeholder, Toast.LENGTH_SHORT).show()
    }

    // 返回键优先退出选择态；抽屉展开时让抽屉先处理返回
    BackHandler(enabled = selectionActive && !drawerState.isOpen) {
        viewModel.clearSelection(state.active)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(state) { tool ->
                Toast.makeText(context, "$tool · 后续里程碑接入", Toast.LENGTH_SHORT).show()
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            if (selectionActive) {
                SelectionTopBar(
                    count = activePane.selected.size,
                    onClose = { viewModel.clearSelection(state.active) },
                    onSelectAll = { viewModel.selectAll(state.active) },
                )
            } else {
                BrowserTopBar(
                    active = state.active,
                    dualPane = state.dualPane,
                    onActiveChange = viewModel::setActive,
                    onMenu = { scope.launch { drawerState.open() } },
                    showHidden = state.showHidden,
                    onRefresh = viewModel::refreshAll,
                    onToggleHidden = viewModel::toggleHidden,
                    onToggleDualPane = { viewModel.setDualPane(!state.dualPane) },
                    onSwap = viewModel::swapPanes,
                    onAbout = { showAbout = true },
                )
            }

            if (!hasPermission) {
                PermissionBanner(onGrant = { openAllFilesSettings(context) })
            }

            BrowserBreadcrumbBar(
                pane = activePane,
                sort = state.sort,
                onNavigate = { path -> viewModel.navigate(state.active, path) },
                onSort = viewModel::setSort,
                onRefresh = { viewModel.refresh(state.active) },
            )

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PaneContent(
                    pane = state.left,
                    active = state.active == PaneId.LEFT,
                    listState = leftListState,
                    onActivate = { viewModel.setActive(PaneId.LEFT) },
                    onOpenDir = { viewModel.navigate(PaneId.LEFT, it) },
                    onOpenFile = { entry ->
                        Toast.makeText(context, "打开 ${entry.name}：M2 接入", Toast.LENGTH_SHORT).show()
                    },
                    onToggleSelect = { entry -> viewModel.toggleSelect(PaneId.LEFT, entry.path) },
                    onRetry = { viewModel.refresh(PaneId.LEFT) },
                    onNewFolder = notImplemented,
                    modifier = Modifier.weight(1f),
                )
                if (state.dualPane) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    PaneContent(
                        pane = state.right,
                        active = state.active == PaneId.RIGHT,
                        listState = rightListState,
                        onActivate = { viewModel.setActive(PaneId.RIGHT) },
                        onOpenDir = { viewModel.navigate(PaneId.RIGHT, it) },
                        onOpenFile = { entry ->
                            Toast.makeText(context, "打开 ${entry.name}：M2 接入", Toast.LENGTH_SHORT).show()
                        },
                        onToggleSelect = { entry -> viewModel.toggleSelect(PaneId.RIGHT, entry.path) },
                        onRetry = { viewModel.refresh(PaneId.RIGHT) },
                        onNewFolder = notImplemented,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (selectionActive) {
                SelectionBottomBar(
                    onCopy = notImplemented,
                    onMove = notImplemented,
                    onDelete = notImplemented,
                    onMoreAction = notImplemented,
                )
            } else {
                BrowserBottomBar(
                    pane = activePane,
                    onBack = { viewModel.goBack(state.active) },
                    onForward = { viewModel.goForward(state.active) },
                    onNew = { Toast.makeText(context, "新建：M2 接入", Toast.LENGTH_SHORT).show() },
                    onSwap = viewModel::swapPanes,
                    onUp = { viewModel.navigateUp(state.active) },
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun BrowserTopBar(
    active: PaneId,
    dualPane: Boolean,
    onActiveChange: (PaneId) -> Unit,
    onMenu: () -> Unit,
    showHidden: Boolean,
    onRefresh: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleDualPane: () -> Unit,
    onSwap: () -> Unit,
    onAbout: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = "打开工具抽屉",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
        ) {
            PaneTab("左窗格", active == PaneId.LEFT) { onActiveChange(PaneId.LEFT) }
            PaneTab("右窗格", active == PaneId.RIGHT, enabled = dualPane) {
                onActiveChange(PaneId.RIGHT)
            }
        }

        Box {
            IconButton(onClick = { overflowOpen = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                DropdownMenuItem(
                    text = { Text("刷新") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (showHidden) "隐藏隐藏文件" else "显示隐藏文件") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (showHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onToggleHidden()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_single_pane_mode)) },
                    leadingIcon = { Icon(Icons.Outlined.ViewColumn, contentDescription = null) },
                    trailingIcon = {
                        Switch(
                            checked = !dualPane,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onToggleDualPane()
                    },
                )
                DropdownMenuItem(
                    text = { Text("交换窗格") },
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onSwap()
                    },
                )
                DropdownMenuItem(
                    text = { Text("关于 VVFile") },
                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onAbout()
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.PaneTab(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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
            .height(56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ui_selection_close),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.ui_selection_title, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        TextButton(
            onClick = onSelectAll,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.SelectAll,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ui_selection_all))
        }
    }
}

@Composable
private fun BrowserBreadcrumbBar(
    pane: PaneState,
    sort: SortSpec,
    onNavigate: (String) -> Unit,
    onSort: (SortField) -> Unit,
    onRefresh: () -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathBar(
            path = pane.path,
            onNavigate = onNavigate,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { sortOpen = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Sort,
                    contentDescription = "排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortItem("名称", SortField.NAME, sort, onSort) { sortOpen = false }
                SortItem("修改时间", SortField.DATE, sort, onSort) { sortOpen = false }
                SortItem("大小", SortField.SIZE, sort, onSort) { sortOpen = false }
                SortItem("类型", SortField.TYPE, sort, onSort) { sortOpen = false }
                DropdownMenuItem(
                    text = { Text(if (sort.ascending) "当前：升序，点击改为降序" else "当前：降序，点击改为升序") },
                    onClick = {
                        sortOpen = false
                        onSort(sort.field)
                    },
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "刷新",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SortItem(
    label: String,
    field: SortField,
    sort: SortSpec,
    onSort: (SortField) -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (sort.field == field) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        onClick = {
            onClose()
            onSort(field)
        },
    )
}

@Composable
private fun BrowserBottomBar(
    pane: PaneState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNew: () -> Unit,
    onSwap: () -> Unit,
    onUp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(58.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavAction(Icons.AutoMirrored.Outlined.ArrowBack, "后退", pane.canGoBack, Modifier.weight(1f), onBack)
        NavAction(Icons.AutoMirrored.Outlined.ArrowForward, "前进", pane.canGoForward, Modifier.weight(1f), onForward)
        NavAction(Icons.Outlined.Add, "新建", true, Modifier.weight(1f), onNew)
        NavAction(Icons.Outlined.SwapHoriz, "交换", true, Modifier.weight(1f), onSwap)
        NavAction(Icons.Outlined.ArrowUpward, "上级", pane.path != "/", Modifier.weight(1f), onUp)
    }
}

/** 选择态底部工具条：复制 / 移动 / 删除 / 更多。 */
@Composable
private fun SelectionBottomBar(
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onMoreAction: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(58.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavAction(
            icon = Icons.Outlined.ContentCopy,
            label = stringResource(R.string.ui_action_copy),
            enabled = true,
            modifier = Modifier.weight(1f),
            onClick = onCopy,
        )
        NavAction(
            icon = Icons.AutoMirrored.Outlined.DriveFileMove,
            label = stringResource(R.string.ui_action_move),
            enabled = true,
            modifier = Modifier.weight(1f),
            onClick = onMove,
        )
        NavAction(
            icon = Icons.Outlined.DeleteOutline,
            label = stringResource(R.string.ui_action_delete),
            enabled = true,
            modifier = Modifier.weight(1f),
            tint = VVDanger,
            onClick = onDelete,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            NavAction(
                icon = Icons.Outlined.MoreHoriz,
                label = stringResource(R.string.ui_action_more),
                enabled = true,
                modifier = Modifier.fillMaxSize(),
                onClick = { moreOpen = true },
            )
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                SelectionMoreItem(
                    icon = Icons.Outlined.Edit,
                    label = stringResource(R.string.ui_action_rename),
                    onAction = onMoreAction,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.FolderZip,
                    label = stringResource(R.string.ui_action_compress),
                    onAction = onMoreAction,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.Share,
                    label = stringResource(R.string.ui_action_share),
                    onAction = onMoreAction,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.ui_action_properties),
                    onAction = onMoreAction,
                    onClose = { moreOpen = false },
                )
            }
        }
    }
}

@Composable
private fun SelectionMoreItem(
    icon: ImageVector,
    label: String,
    onAction: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = {
            onClose()
            onAction()
        },
    )
}

@Composable
private fun NavAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val baseTint = tint ?: MaterialTheme.colorScheme.onSurface
    val resolvedTint = if (enabled) {
        baseTint
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = resolvedTint,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = resolvedTint,
        )
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VVWarn.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = VVWarn,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "需要「所有文件访问权限」",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "否则无法浏览共享存储",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onGrant) { Text("去授权") }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 VVFile") },
        text = {
            Text(
                "VVFile 0.1.0（M0）\n\n" +
                    "专注本地文件管理：双窗格浏览、排序、隐藏文件、存储信息。\n" +
                    "不包含 APK 修改、会员、插件、回收站、MCP 等功能。\n\n" +
                    "后续里程碑：文件操作 → root 与系统分区 → 终端 / 编辑器 / 应用管理。",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

private fun hasStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    } else {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
}
