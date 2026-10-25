package com.vvfile.app.feature.browser
import androidx.compose.ui.graphics.RectangleShape

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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
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
import com.vvfile.app.BuildConfig
import com.vvfile.app.R
import com.vvfile.app.core.path.PathUtils
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.core.script.ScriptEnv
import com.vvfile.app.core.script.ScriptExecutionPrefs
import com.vvfile.app.core.script.ScriptRunResult
import com.vvfile.app.core.script.ScriptRunner
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.core.share.FileSharer
import com.vvfile.app.data.file.AggregateProperties
import com.vvfile.app.data.file.AggregatePropertyReader
import com.vvfile.app.data.file.FileProperties
import com.vvfile.app.data.file.FilePropertyReader
import com.vvfile.app.data.prefs.PrefsRepository
import com.vvfile.app.feature.media.MediaFormats
import com.vvfile.app.data.prefs.Settings as AppSettings
import com.vvfile.app.ui.components.PathBar
import com.vvfile.app.ui.components.PathInputDialog
import com.vvfile.app.ui.theme.VVDanger
import com.vvfile.app.ui.theme.VVWarn
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = viewModel(),
    onOpenFile: ((String) -> Unit)? = null,
    onToolClick: ((String) -> Unit)? = null,
    /** 外部（如应用信息弹窗）请求跳转的路径；消费后回调 [onNavigateHandled]。 */
    navigateTo: String? = null,
    onNavigateHandled: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var hasPermission by remember { mutableStateOf(hasStoragePermission(context)) }
    var showAbout by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf<FileProperties?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var showPathInput by remember { mutableStateOf(false) }
    var showUnzipTo by remember { mutableStateOf(false) }
    var aggregate by remember { mutableStateOf<AggregateProperties?>(null) }
    var chmodRequest by remember { mutableStateOf<ChmodRequest?>(null) }

    // 每个窗格一份滚动状态；路径变化时回到顶部（requestScrollToItem 在列表尚未布局时也生效）
    val leftListState = rememberLazyListState()
    val rightListState = rememberLazyListState()
    LaunchedEffect(state.left.path) { leftListState.requestScrollToItem(0) }
    LaunchedEffect(state.right.path) { rightListState.requestScrollToItem(0) }
    // 排序变化后两窗格都回到顶部，避免新排序下首行被裁成半截
    LaunchedEffect(state.sort) {
        leftListState.requestScrollToItem(0)
        rightListState.requestScrollToItem(0)
    }
    // 过滤条件变化后回到顶部，避免沿用旧索引显示到列表中部
    LaunchedEffect(state.left.filterQuery, state.left.filterExtensions) {
        leftListState.requestScrollToItem(0)
    }
    LaunchedEffect(state.right.filterQuery, state.right.filterExtensions) {
        rightListState.requestScrollToItem(0)
    }

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
    /** 共享存储根（`/storage/emulated/0`），顶部菜单「回到根目录」用它。 */
    val sharedRoot = remember {
        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "/storage/emulated/0"
    }

    val settings by remember { PrefsRepository.get(context).settings }
        .collectAsStateWithLifecycle(initialValue = AppSettings.DEFAULT)
    LaunchedEffect(settings) {
        viewModel.applySettings(settings)
    }
    val selectionActive = activePane.selectionMode
    val activeSelectedEntries = activePane.entries.filter { it.path in activePane.selected }
    val singleSelected = activeSelectedEntries.singleOrNull()
    val canUnzip = singleSelected != null && !singleSelected.isDirectory && singleSelected.extension == "zip"
    val defaultFolderName = stringResource(R.string.ui_ops_default_folder)
    val defaultFileName = stringResource(R.string.ui_ops_default_file)
    val defaultZipName = stringResource(R.string.ui_ops_zip_default_multi)
    var actionTarget by remember { mutableStateOf<FileActionTarget?>(null) }
    var openWithTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var scriptTarget by remember { mutableStateOf<FileEntry?>(null) }
    var scriptResult by remember { mutableStateOf<Pair<String, ScriptRunResult>?>(null) }
    val scriptRunner = remember { ScriptRunner() }
    var scriptEnv by remember { mutableStateOf(ScriptExecutionPrefs.env) }
    var scriptPermission by remember { mutableStateOf(ScriptExecutionPrefs.permission) }
    var scriptCurrentMode by remember { mutableStateOf<PermissionMode?>(null) }
    var nameDialog by remember { mutableStateOf<NameDialogState?>(null) }
    /** 批量重命名的目标条目；非空时展示对话框。 */
    var batchRenameTargets by remember { mutableStateOf<List<FileEntry>?>(null) }

    /**
     * 长按打开操作菜单（对齐 MT）：按下的条目已经在选区里就作用于整批，否则把它设为唯一选中项。
     *
     * 旧实现无条件「清空选区再选这一条」，多选 14 个文件后长按其中任意一个，
     * 选区就被打散成一个，后面的复制/移动全变成了单条操作。
     */
    val openActionMenu: (PaneId, FileEntry) -> Unit = { paneId, entry ->
        viewModel.setActive(paneId)
        val pane = if (paneId == PaneId.LEFT) state.left else state.right
        val targets = if (entry.path in pane.selected) {
            pane.entries.filter { it.path in pane.selected }
        } else {
            viewModel.clearSelection(paneId)
            viewModel.toggleSelect(paneId, entry.path)
            listOf(entry)
        }
        actionTarget = FileActionTarget(entry, paneId, targets.ifEmpty { listOf(entry) })
    }

    /** 单条 → 单条属性；多条 → 聚合属性（递归统计合计大小与文件/文件夹数）。 */
    val openProperties: (List<FileEntry>) -> Unit = { entries ->
        val directory = activePane.path
        when (entries.size) {
            0 -> Unit
            1 -> scope.launch { properties = FilePropertyReader.read(entries.first().path) }
            else -> {
                val paths = entries.map { it.path }
                scope.launch { aggregate = AggregatePropertyReader.read(paths, directory) }
            }
        }
    }

    /**
     * 打开脚本菜单：把文件**当前**的权限位读出来并预选。
     *
     * 这个面板里的权限是「执行前 chmod 成什么」，所以默认值必须是文件真实的权限，
     * 而不是上一次记住的值——否则用户看到的和执行的实际值对不上。
     */
    val openScript: (FileEntry) -> Unit = { entry ->
        val fromListing = entry.permissions?.let { PermissionMode.fromSymbolic(it) }
        scriptCurrentMode = fromListing
        if (fromListing != null) scriptPermission = fromListing.octal().takeLast(3)
        scriptTarget = entry
        if (fromListing == null) {
            // 列表没带权限（部分 FUSE 卷），补读一次属性
            scope.launch {
                val mode = runCatching { FilePropertyReader.read(entry.path) }.getOrNull()
                    ?.permissions
                    ?.let { PermissionMode.fromSymbolic(it) }
                if (mode != null && scriptTarget?.path == entry.path) {
                    scriptCurrentMode = mode
                    scriptPermission = mode.octal().takeLast(3)
                }
            }
        }
    }

    // 一次性操作提示：展示后立即消费，避免重组重复弹出
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = when (message) {
            is UiMessage.Result -> formatResult(context, message)
            is UiMessage.Simple -> context.getString(message.textRes)
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    // 外部跳转请求（应用信息弹窗点数据目录）：交给当前窗格，消费后清空
    LaunchedEffect(navigateTo) {
        val target = navigateTo ?: return@LaunchedEffect
        viewModel.navigate(state.active, target)
        onNavigateHandled?.invoke()
    }

    // 返回键 / 全面屏侧滑：选择态 → 抽屉 → 上一层目录。
    // 已经在根目录时**什么都不做**：以前这里不拦，一滑就把应用退回桌面，误触成本太高。
    val canNavigateUp = remember(activePane.path) { File(activePane.path).parent != null }
    BackHandler(enabled = true) {
        when {
            selectionActive -> viewModel.clearSelection(state.active)
            drawerState.isOpen -> scope.launch { drawerState.close() }
            canNavigateUp -> viewModel.navigateUp(state.active)
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        // 侧滑手势留给系统返回（上一层目录），抽屉只从菜单按钮打开，避免边滑被抽屉截走
        gesturesEnabled = false,
        drawerContent = {
            DrawerContent(
                state = state,
                onAbout = {
                    scope.launch { drawerState.close() }
                    showAbout = true
                },
            ) { tool ->
                scope.launch { drawerState.close() }
                if (onToolClick != null) {
                    onToolClick(tool)
                } else {
                    Toast.makeText(context, "$tool · 后续里程碑接入", Toast.LENGTH_SHORT).show()
                }
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
                    onInvertSelection = { viewModel.invertSelection(state.active) },
                )
            } else {
                BrowserTopBar(
                    active = state.active,
                    onActiveChange = viewModel::setActive,
                    onMenu = { scope.launch { drawerState.open() } },
                    showHidden = state.showHidden,
                    onToggleHidden = viewModel::toggleHidden,
                    onSync = viewModel::syncOtherPane,
                    onHome = {
                        // 回共享存储根；路径没变时 navigate 里会走刷新
                        viewModel.navigate(state.active, sharedRoot)
                    },
                )
            }

            if (!hasPermission) {
                PermissionBanner(onGrant = { openAllFilesSettings(context) })
            }

            BrowserBreadcrumbBar(
                pane = activePane,
                sort = state.sort,
                onEditPath = { showPathInput = true },
                onSort = viewModel::setSort,
                onFilter = { showFilter = true },
                onRefresh = { viewModel.refresh(state.active) },
            )

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                PaneContent(
                    pane = state.left,
                    active = state.active == PaneId.LEFT,
                    listState = leftListState,
                    showDetails = settings.detailedList,
                    onActivate = { viewModel.setActive(PaneId.LEFT) },
                    onOpenDir = { path ->
                        viewModel.setActive(PaneId.LEFT)
                        viewModel.navigate(PaneId.LEFT, path)
                    },
                    onOpenFile = { entry ->
                        viewModel.setActive(PaneId.LEFT)
                        if (entry.isScript) {
                            openScript(entry)
                        } else if (onOpenFile != null) {
                            onOpenFile(entry.path)
                        } else {
                            Toast.makeText(context, "打开 ${entry.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongPress = { entry -> openActionMenu(PaneId.LEFT, entry) },
                    onToggleSelect = { entry -> viewModel.toggleSelect(PaneId.LEFT, entry.path) },
                    onRetry = { viewModel.refresh(PaneId.LEFT) },
                    onNewFolder = {
                        viewModel.setActive(PaneId.LEFT)
                        nameDialog = NameDialogState(NameDialogMode.NEW_FOLDER, defaultFolderName)
                    },
                    onPullRefresh = { viewModel.refresh(PaneId.LEFT) },
                    modifier = Modifier.weight(1f),
                )
                run {
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
                        showDetails = settings.detailedList,
                        onActivate = { viewModel.setActive(PaneId.RIGHT) },
                        onOpenDir = { path ->
                            viewModel.setActive(PaneId.RIGHT)
                            viewModel.navigate(PaneId.RIGHT, path)
                        },
                        onOpenFile = { entry ->
                            viewModel.setActive(PaneId.RIGHT)
                            if (entry.isScript) {
                                openScript(entry)
                            } else if (onOpenFile != null) {
                                onOpenFile(entry.path)
                            } else {
                                Toast.makeText(context, "打开 ${entry.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onLongPress = { entry -> openActionMenu(PaneId.RIGHT, entry) },
                        onToggleSelect = { entry -> viewModel.toggleSelect(PaneId.RIGHT, entry.path) },
                        onRetry = { viewModel.refresh(PaneId.RIGHT) },
                        onNewFolder = {
                            viewModel.setActive(PaneId.RIGHT)
                            nameDialog = NameDialogState(NameDialogMode.NEW_FOLDER, defaultFolderName)
                        },
                        onPullRefresh = { viewModel.refresh(PaneId.RIGHT) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (selectionActive) {
                SelectionBottomBar(
                    onCopy = viewModel::copySelectionToOtherPane,
                    onMove = viewModel::moveSelectionToOtherPane,
                    onDelete = { showDeleteConfirm = true },
                    multi = activeSelectedEntries.size > 1,
                    onRename = {
                        if (activeSelectedEntries.size > 1) {
                            batchRenameTargets = activeSelectedEntries
                        } else {
                            singleSelected?.let {
                                nameDialog = NameDialogState(NameDialogMode.RENAME, it.name)
                            }
                        }
                    },
                    onCompress = {
                        val base = singleSelected?.let { defaultZipBaseName(it) }
                        nameDialog = NameDialogState(
                            NameDialogMode.ZIP,
                            if (base != null) "$base.zip" else defaultZipName,
                        )
                    },
                    canUnzip = canUnzip,
                    onUnzip = { viewModel.unzipSelection() },
                    onUnzipTo = { showUnzipTo = true },
                    onShare = {
                        FileSharer.share(context, activePane.selected.toList())
                    },
                    onProperties = { openProperties(activeSelectedEntries) },
                )
            } else {
                BrowserBottomBar(
                    pane = activePane,
                    onBack = { viewModel.goBack(state.active) },
                    onForward = { viewModel.goForward(state.active) },
                    onNewFolder = {
                        nameDialog = NameDialogState(NameDialogMode.NEW_FOLDER, defaultFolderName)
                    },
                    onNewFile = {
                        nameDialog = NameDialogState(NameDialogMode.NEW_FILE, defaultFileName)
                    },
                    onSync = viewModel::syncOtherPane,
                    onUp = { viewModel.navigateUp(state.active) },
                )
            }
        }

    }

        // 抽屉打开时，点侧栏右侧未覆盖区域关闭。
        // material3 的遮罩在 gesturesEnabled = false 时会吞掉点击却不关闭，
        // 所以这里在抽屉整体之上单独盖一条「右侧空白」，只在打开时存在。
        if (drawerState.isOpen) {
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(DRAWER_WIDTH))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            scope.launch { drawerState.close() }
                        },
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    scriptTarget?.let { entry ->
        ScriptRunDialog(
            fileName = entry.name,
            env = scriptEnv,
            permission = scriptPermission,
            currentMode = scriptCurrentMode,
            onEnvChange = {
                scriptEnv = it
                ScriptExecutionPrefs.env = it
            },
            onPermissionChange = {
                scriptPermission = it
                ScriptExecutionPrefs.permission = it
            },
            onEdit = {
                scriptTarget = null
                if (onOpenFile != null) {
                    onOpenFile(entry.path)
                }
            },
            onRun = {
                scriptTarget = null
                if (scriptEnv == ScriptEnv.ROOT && !RootManager.isAvailable()) {
                    Toast.makeText(context, R.string.terminal_root_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    val target = entry
                    val env = scriptEnv
                    val permission = scriptPermission
                    scope.launch {
                        val outcome = runCatching {
                            scriptRunner.run(target.path, env, permission)
                        }.getOrNull()
                        if (outcome != null) {
                            scriptResult = target.name to outcome
                        } else {
                            Toast.makeText(context, "执行失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = { scriptTarget = null },
        )
    }

    scriptResult?.let { (name, outcome) ->
        ScriptResultDialog(
            fileName = name,
            result = outcome,
            onDismiss = { scriptResult = null },
        )
    }

    actionTarget?.let { target ->
        val entry = target.entry
        val targets = target.targets
        FileActionMenu(
            target = target,
            onDismiss = { actionTarget = null },
            onCopy = viewModel::copySelectionToOtherPane,
            onMove = viewModel::moveSelectionToOtherPane,
            onDelete = { showDeleteConfirm = true },
            onRename = { nameDialog = NameDialogState(NameDialogMode.RENAME, entry.name) },
            onBatchRename = { batchRenameTargets = targets },
            onCompress = {
                val base = entry.name.substringBeforeLast('.').ifBlank { entry.name }
                nameDialog = NameDialogState(NameDialogMode.ZIP, "$base.zip")
            },
            onUnzip = viewModel::unzipSelection,
            onUnzipTo = { showUnzipTo = true },
            onProperties = { openProperties(targets) },
            onShare = { FileSharer.share(context, targets.map { it.path }) },
            onOpenWith = { openWithTarget = entry },
        )
    }

    openWithTarget?.let { entry ->
        OpenWithDialog(
            path = entry.path,
            fileName = entry.name,
            onEditText = if (MimeTypes.isText(entry.path)) {
                { onOpenFile?.invoke(entry.path) }
            } else {
                null
            },
            onDismiss = { openWithTarget = null },
            onOpenInternal = if (MediaFormats.isMedia(entry.path)) {
                { onOpenFile?.invoke(entry.path) }
            } else {
                null
            },
        )
    }

    properties?.let { info ->
        PropertiesDialog(
            properties = info,
            onDismiss = { properties = null },
            onChangePermissions = {
                properties = null
                chmodRequest = ChmodRequest(
                    paths = listOf(info.path),
                    mode = PermissionMode.fromSymbolic(info.permissions) ?: PermissionMode.DEFAULT,
                )
            },
        )
    }

    aggregate?.let { info ->
        MultiPropertiesDialog(
            properties = info,
            onDismiss = { aggregate = null },
            onChangePermissions = {
                aggregate = null
                chmodRequest = ChmodRequest(
                    paths = info.paths,
                    mode = info.commonPermissions?.let { PermissionMode.fromSymbolic(it) }
                        ?: PermissionMode.DEFAULT,
                )
            },
        )
    }

    chmodRequest?.let { request ->
        PermissionDialog(
            summary = if (request.paths.size == 1) {
                request.paths.first()
            } else {
                "已选 ${request.paths.size} 项"
            },
            initialMode = request.mode,
            onConfirm = { octal ->
                chmodRequest = null
                viewModel.changePermissions(request.paths, octal)
            },
            onDismiss = { chmodRequest = null },
        )
    }

    batchRenameTargets?.let { entries ->
        BatchRenameDialog(
            targets = entries.map { RenameTarget(it.name, it.path, it.isDirectory) },
            existingNames = activePane.entries.mapTo(HashSet()) { it.name },
            onDismiss = { batchRenameTargets = null },
            onConfirm = { previews ->
                batchRenameTargets = null
                val renames = previews.mapNotNull { preview ->
                    entries.firstOrNull { it.name == preview.original }
                        ?.let { entry -> entry.path to preview.result }
                }
                viewModel.batchRename(renames)
            },
        )
    }

    if (showUnzipTo) {
        UnzipToDialog(
            defaultPath = activePane.path,
            onConfirm = { path ->
                showUnzipTo = false
                viewModel.unzipSelection(path)
            },
            onDismiss = { showUnzipTo = false },
        )
    }

    if (state.rootPrompt) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRootNavigation,
            title = { Text(stringResource(R.string.ui_root_prompt_title)) },
            text = { Text(stringResource(R.string.ui_root_prompt_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRootNavigation) {
                    Text(stringResource(R.string.ui_root_prompt_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRootNavigation) {
                    Text(stringResource(R.string.ui_root_prompt_cancel))
                }
            },
        )
    }

    if (showPathInput) {
        PathInputDialog(
            currentPath = activePane.path,
            onConfirm = { path ->
                showPathInput = false
                if (path != activePane.path) viewModel.navigate(state.active, path)
            },
            onDismiss = { showPathInput = false },
            suggest = { query -> viewModel.suggestPaths(query) },
        )
    }

    if (showFilter) {
        FilterDialog(
            initialQuery = activePane.filterQuery,
            initialExtensions = activePane.filterExtensions,
            onApply = { query, extensions ->
                viewModel.setFilter(state.active, query, extensions)
                showFilter = false
            },
            onClear = {
                viewModel.setFilter(state.active, "", "")
                showFilter = false
            },
            onDismiss = { showFilter = false },
        )
    }

    state.operation?.let { operation ->
        OperationProgressDialog(operation = operation, onCancel = viewModel::cancelOperation)
    }

    state.conflictPrompt?.let { prompt ->
        ConflictDialog(prompt = prompt, onResolve = viewModel::resolveConflict)
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            count = activePane.selected.size,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteSelection()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    nameDialog?.let { dialog ->
        NameInputDialog(
            state = dialog,
            onDismiss = { nameDialog = null },
            onConfirm = { name, level ->
                nameDialog = null
                when (dialog.mode) {
                    NameDialogMode.RENAME -> viewModel.renameSelection(name)
                    NameDialogMode.NEW_FOLDER -> viewModel.createFolder(name)
                    NameDialogMode.NEW_FILE -> viewModel.createFile(name)
                    NameDialogMode.ZIP -> viewModel.zipSelection(name, level)
                }
            },
        )
    }
}

@Composable
private fun BrowserTopBar(
    active: PaneId,
    onActiveChange: (PaneId) -> Unit,
    onMenu: () -> Unit,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onSync: () -> Unit,
    onHome: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
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
                .height(32.dp)
                .padding(horizontal = 4.dp)
                .clip(RectangleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(3.dp),
        ) {
            PaneTab("左窗格", active == PaneId.LEFT) { onActiveChange(PaneId.LEFT) }
            PaneTab("右窗格", active == PaneId.RIGHT) {
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
                    text = { Text("回到根目录") },
                    leadingIcon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onHome()
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
                    text = { Text("同步窗格") },
                    leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) },
                    onClick = {
                        overflowOpen = false
                        onSync()
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
            .clip(RectangleShape)
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
    onInvertSelection: () -> Unit,
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
        TextButton(
            onClick = onInvertSelection,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.ui_selection_invert))
        }
    }
}

@Composable
private fun BrowserBreadcrumbBar(
    pane: PaneState,
    sort: SortSpec,
    onEditPath: () -> Unit,
    onSort: (SortField) -> Unit,
    onFilter: () -> Unit,
    onRefresh: () -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PathBar(
            path = pane.path,
            onEditPath = onEditPath,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = onFilter) {
                Icon(
                    imageVector = Icons.Outlined.FilterAlt,
                    contentDescription = stringResource(R.string.ui_filter_title),
                    tint = if (pane.filterActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
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
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onSync: () -> Unit,
    onUp: () -> Unit,
) {
    var newOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(54.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavAction(Icons.AutoMirrored.Outlined.ArrowBack, "后退", pane.canGoBack, Modifier.weight(1f), onBack)
        NavAction(Icons.AutoMirrored.Outlined.ArrowForward, "前进", pane.canGoForward, Modifier.weight(1f), onForward)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            NavAction(
                icon = Icons.Outlined.Add,
                label = "新建",
                enabled = true,
                modifier = Modifier.fillMaxSize(),
                onClick = { newOpen = true },
            )
            DropdownMenu(expanded = newOpen, onDismissRequest = { newOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_ops_new_folder_title)) },
                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    onClick = {
                        newOpen = false
                        onNewFolder()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_ops_new_file_title)) },
                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    onClick = {
                        newOpen = false
                        onNewFile()
                    },
                )
            }
        }
        NavAction(Icons.Outlined.Sync, "同步", true, Modifier.weight(1f), onSync)
        NavAction(Icons.Outlined.ArrowUpward, "上级", pane.path != "/", Modifier.weight(1f), onUp)
    }
}

/** 选择态底部工具条：复制 / 移动 / 删除 / 更多。 */
@Composable
private fun SelectionBottomBar(
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    multi: Boolean,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    canUnzip: Boolean,
    onUnzip: () -> Unit,
    onUnzipTo: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .height(54.dp)
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
                    label = if (multi) "批量重命名" else stringResource(R.string.ui_action_rename),
                    onAction = onRename,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.FolderZip,
                    label = stringResource(R.string.ui_action_compress),
                    onAction = onCompress,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.ui_action_unzip),
                    enabled = canUnzip,
                    onAction = onUnzip,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.FolderOpen,
                    label = stringResource(R.string.ui_action_unzip_to),
                    enabled = canUnzip,
                    onAction = onUnzipTo,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.Share,
                    label = stringResource(R.string.ui_action_share),
                    onAction = onShare,
                    onClose = { moreOpen = false },
                )
                SelectionMoreItem(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.ui_action_properties),
                    onAction = onProperties,
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
    enabled: Boolean = true,
    onAction: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        enabled = enabled,
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
            .clip(RectangleShape)
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
                "VVFile ${BuildConfig.VERSION_NAME}\n\n" +
                    "专注本地文件管理：双窗格浏览、批量选择、复制/移动/删除/重命名、\n" +
                    "压缩与解压、root 系统分区读写、终端模拟器、文本编辑器。\n" +
                    "不包含 APK 修改、会员、插件、回收站、MCP 等功能。\n\n" +
                    "root 能力仅依赖设备上的 su，不接入 Shizuku / ADB。",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

private enum class NameDialogMode { RENAME, NEW_FOLDER, NEW_FILE, ZIP }

private data class NameDialogState(
    val mode: NameDialogMode,
    val initialName: String,
)

@Composable
private fun UnzipToDialog(
    defaultPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(defaultPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_unzip_to_title)) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(stringResource(R.string.ui_unzip_to_path)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { path.trim().takeIf { it.isNotEmpty() }?.let(onConfirm) },
            ) {
                Text(stringResource(R.string.ui_unzip_to_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_filter_cancel))
            }
        },
    )
}

@Composable
private fun FilterDialog(
    initialQuery: String,
    initialExtensions: String,
    onApply: (String, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var extensions by remember { mutableStateOf(initialExtensions) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_filter_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.ui_filter_query)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = extensions,
                    onValueChange = { extensions = it },
                    label = { Text(stringResource(R.string.ui_filter_extensions)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(query, extensions) }) {
                Text(stringResource(R.string.ui_filter_apply))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.ui_filter_clear))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ui_filter_cancel))
                }
            }
        },
    )
}

@Composable
private fun PropertiesDialog(
    properties: FileProperties,
    onDismiss: () -> Unit,
    onChangePermissions: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = properties.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                PropertyRow("类型", properties.typeText)
                PropertyRow("路径", properties.path)
                PropertyRow("大小", properties.sizeText)
                PropertyRow("修改时间", properties.modifiedText)
                PropertyRow("权限", properties.permissions)
                PropertyRow("所有者", properties.owner)
                PropertyRow(
                    "可读 / 可写",
                    "${if (properties.readable) "是" else "否"} / ${if (properties.writable) "是" else "否"}",
                )
                if (properties.isSymlink) {
                    PropertyRow("符号链接", "是")
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onChangePermissions) {
                    Text(stringResource(R.string.ui_chmod_title))
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * 改权限的入参：[paths] 为单条或整批，[mode] 是从属性页回填的当前权限位。
 */
private data class ChmodRequest(val paths: List<String>, val mode: PermissionMode)

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(78.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 压缩默认名：去掉最后一个扩展名，保留 `.hidden` 这类点开头的完整名称。 */
private fun defaultZipBaseName(entry: FileEntry): String {
    val dot = entry.name.lastIndexOf('.')
    return if (dot > 0) entry.name.substring(0, dot) else entry.name
}

/** 操作摘要 → Toast 文案。 */
private fun formatResult(context: Context, message: UiMessage.Result): String {
    if (message.cancelled) return context.getString(R.string.ui_ops_result_cancelled)
    val reason = message.failureReason?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.ui_ops_unknown_error)
    return when {
        message.failed > 0 && message.succeeded == 0 ->
            context.getString(R.string.ui_ops_result_failed, reason)
        message.failed > 0 ->
            context.getString(R.string.ui_ops_result_partial, message.succeeded, message.failed)
        message.skipped > 0 ->
            context.getString(R.string.ui_ops_result_skipped, message.succeeded, message.skipped)
        message.succeeded > 0 ->
            context.getString(R.string.ui_ops_result_done_count, message.succeeded)
        else -> context.getString(R.string.ui_ops_result_done)
    }
}

@Composable
private fun OperationProgressDialog(operation: OperationUiState, onCancel: () -> Unit) {
    val title = stringResource(
        when (operation.kind) {
            OperationKind.COPY -> R.string.ui_ops_copy_title
            OperationKind.MOVE -> R.string.ui_ops_move_title
            OperationKind.DELETE -> R.string.ui_ops_delete_progress_title
            OperationKind.ZIP -> R.string.ui_ops_zip_progress_title
            OperationKind.UNZIP -> R.string.ui_ops_unzip_progress_title
        },
    )
    val percent = (operation.progress * 100f).toInt().coerceIn(0, 100)
    val barModifier = Modifier
        .fillMaxWidth()
        .height(6.dp)
        .clip(RectangleShape)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = operation.currentName.ifBlank { stringResource(R.string.ui_ops_preparing) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (operation.preparing) {
                    LinearProgressIndicator(
                        modifier = barModifier,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { operation.progress },
                        modifier = barModifier,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (operation.totalItems > 0) {
                            stringResource(
                                R.string.ui_ops_progress_items,
                                operation.processedItems,
                                operation.totalItems,
                            )
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.ui_ops_progress_percent, percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_ops_cancel)) }
        },
    )
}

@Composable
private fun ConflictDialog(prompt: ConflictPrompt, onResolve: (ConflictPolicy?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResolve(null) },
        title = { Text(stringResource(R.string.ui_ops_conflict_title)) },
        text = { Text(stringResource(R.string.ui_ops_conflict_message, prompt.count)) },
        confirmButton = {
            Row {
                TextButton(onClick = { onResolve(ConflictPolicy.REPLACE) }) {
                    Text(stringResource(R.string.ui_ops_conflict_replace))
                }
                TextButton(onClick = { onResolve(ConflictPolicy.SKIP) }) {
                    Text(stringResource(R.string.ui_ops_conflict_skip))
                }
                TextButton(onClick = { onResolve(ConflictPolicy.RENAME) }) {
                    Text(stringResource(R.string.ui_ops_conflict_rename))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(null) }) { Text(stringResource(R.string.ui_ops_cancel)) }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_ops_delete_title)) },
        text = { Text(stringResource(R.string.ui_ops_delete_message, count)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = VVDanger),
            ) {
                Text(stringResource(R.string.ui_ops_confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_ops_cancel)) }
        },
    )
}

@Composable
private fun NameInputDialog(
    state: NameDialogState,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var name by remember(state) { mutableStateOf(state.initialName) }
    var level by remember(state) { mutableStateOf(6) }
    val candidate = name.trim()
    val error = remember(candidate) {
        if (candidate.isEmpty()) {
            null
        } else {
            runCatching { PathUtils.validateFileName(candidate) }.exceptionOrNull()?.message
        }
    }
    val canConfirm = candidate.isNotEmpty() && error == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nameDialogTitle(state.mode)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ui_ops_name_label)) },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.mode == NameDialogMode.ZIP) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.ui_ops_zip_level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0 to R.string.ui_ops_zip_level_store,
                            6 to R.string.ui_ops_zip_level_normal,
                            9 to R.string.ui_ops_zip_level_max,
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = level == value,
                                onClick = { level = value },
                                label = {
                                    Text(
                                        stringResource(label),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(candidate, level) }, enabled = canConfirm) {
                Text(stringResource(R.string.ui_ops_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_ops_cancel)) }
        },
    )
}

@Composable
private fun nameDialogTitle(mode: NameDialogMode): String = stringResource(
    when (mode) {
        NameDialogMode.RENAME -> R.string.ui_ops_rename_title
        NameDialogMode.NEW_FOLDER -> R.string.ui_ops_new_folder_title
        NameDialogMode.NEW_FILE -> R.string.ui_ops_new_file_title
        NameDialogMode.ZIP -> R.string.ui_ops_zip_dialog_title
    },
)

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



