package io.legado.app.ui.book.bookmark

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.UploadState
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.bookmark.BookmarkEditSheet
import io.legado.app.ui.widget.components.bookmark.BookmarkItem
import io.legado.app.ui.widget.components.button.TopBarActionButton
import io.legado.app.ui.widget.components.button.TopBarNavigationButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.list.TopFloatingStickyItem
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.utils.formatReadDuration
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun AllBookmarkScreen(
    viewModel: AllBookmarkViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val uiState by viewModel.uiState.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()
    val contentState = when {
        uiState.isLoading -> "LOADING"
        uiState.groups.isEmpty() -> "EMPTY"
        else -> "CONTENT"
    }
    val searchText = uiState.searchQuery
    val collapsedGroups = uiState.collapsedGroups
    val bookmarkGroups = uiState.groups
    val allKeys = remember(bookmarkGroups) { bookmarkGroups.map { it.header }.toSet() }
    val isAllCollapsed =
        allKeys.isNotEmpty() && allKeys.all { collapsedGroups.contains(it.toString()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var pendingExportIsMd by remember { mutableStateOf(false) }
    // 多选模式
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isMultiSelectMode = selectedIds.isNotEmpty()
    // 待导出的书签（null = 全部，非null = 指定列表）
    var pendingExportList by remember { mutableStateOf<List<Bookmark>?>(null) }
    // 合并对话框状态
    var mergeTargetHeader by remember { mutableStateOf<BookmarkGroupHeader?>(null) }
    // 分组长按菜单状态
    var groupMenuHeader by remember { mutableStateOf<BookmarkGroupUiData?>(null) }
    var showGroupMenu by remember { mutableStateOf(false) }
    val mergeCandidates = remember { mutableStateListOf<String>() }
    val mergeSelected = remember { mutableStateListOf<String>() }
    LaunchedEffect(mergeTargetHeader) {
        val header = mergeTargetHeader ?: return@LaunchedEffect
        val candidates = viewModel.getMergeCandidates(header)
        mergeCandidates.clear()
        mergeCandidates.addAll(candidates)
        mergeSelected.clear()
        mergeSelected.addAll(candidates)
    }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val stickyGroup by remember(bookmarkGroups, collapsedGroups, listState) {
        derivedStateOf {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleGroup = bookmarkGroups.getOrNull(firstVisibleIndex)
                ?: return@derivedStateOf null
            val isCollapsed = collapsedGroups.contains(firstVisibleGroup.header.toString())
            val shouldStick = firstVisibleIndex > 0 || listState.firstVisibleItemScrollOffset > 24
            if (!isCollapsed && shouldStick) firstVisibleGroup.header else null
        }
    }

    // 上传Toast反馈
    LaunchedEffect(uploadState) {
        when (uploadState) {
            UploadState.UPLOADING -> Toast.makeText(context, "上传中...", Toast.LENGTH_SHORT).show()
            UploadState.SUCCESS -> Toast.makeText(context, "上传完成", Toast.LENGTH_SHORT).show()
            UploadState.FAILURE -> Toast.makeText(context, "上传失败", Toast.LENGTH_LONG).show()
            UploadState.IDLE -> Unit
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportBookmarks(it, pendingExportIsMd, pendingExportList)
            pendingExportList = null
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                GlassMediumFlexibleTopAppBar(
                    title = if (isMultiSelectMode) "已选 ${selectedIds.size} 条" else "所有书签",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (isMultiSelectMode) {
                            TopBarNavigationButton(
                                onClick = { selectedIds.clear() },
                                imageVector = Icons.Default.CheckBox,
                                contentDescription = "退出多选"
                            )
                        } else {
                            TopBarNavigationButton(onClick = onBack)
                        }
                    },
                    actions = {
                        if (!isMultiSelectMode) {
                            if (bookmarkGroups.isNotEmpty()) {
                                TopBarActionButton(
                                    onClick = { viewModel.toggleAllCollapse(allKeys) },
                                    imageVector = if (isAllCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                                    contentDescription = null
                                )
                            }
                            TopBarActionButton(
                                onClick = { viewModel.uploadToWebDav() },
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = "上传书签"
                            )
                            TopBarActionButton(
                                onClick = {
                                    showSearch = !showSearch
                                    if (!showSearch) viewModel.onSearchQueryChanged("")
                                },
                                imageVector = Icons.Default.Search,
                                contentDescription = null
                            )
                            TopBarActionButton(
                                onClick = { showMenu = true },
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu"
                            )
                            RoundDropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                RoundDropdownMenuItem(
                                    text = "导出 JSON",
                                    onClick = {
                                        showMenu = false
                                        pendingExportIsMd = false
                                        pendingExportList = null
                                        exportLauncher.launch(null)
                                    }
                                )
                                RoundDropdownMenuItem(
                                    text = "导出 Markdown",
                                    onClick = {
                                        showMenu = false
                                        pendingExportIsMd = true
                                        pendingExportList = null
                                        exportLauncher.launch(null)
                                    }
                                )
                            }
                        }
                    }
                )
                AnimatedVisibility(
                    modifier = Modifier.adaptiveHorizontalPadding(),
                    visible = showSearch && !isMultiSelectMode,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SearchBar(
                        query = searchText,
                        onQueryChange = { viewModel.onSearchQueryChanged(it) },
                        placeholder = "搜索...",
                        scrollState = listState,
                        scope = scope
                    )
                }
            }
        },
        bottomBar = {
            // 多选模式底部操作栏
            AnimatedVisibility(
                visible = isMultiSelectMode,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
            ) {
                val selectedBookmarks = remember(selectedIds.toList(), bookmarkGroups) {
                    bookmarkGroups.flatMap { it.items }
                        .filter { selectedIds.contains(it.id) }
                        .map { it.rawBookmark }
                }
                var showDeleteConfirm by remember { mutableStateOf(false) }
                var batchExportMenu by remember { mutableStateOf(false) }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("批量删除") },
                        text = { Text("确定删除选中的 ${selectedIds.size} 条书签？此操作不可撤销。") },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.deleteBookmarks(selectedBookmarks)
                                selectedIds.clear()
                                showDeleteConfirm = false
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                        }
                    )
                }

                BottomAppBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 全选/取消全选
                        val allItemIds = bookmarkGroups.flatMap { it.items }.map { it.id }
                        val isAllSelected = allItemIds.isNotEmpty() && selectedIds.containsAll(allItemIds)
                        IconButton(onClick = {
                            if (isAllSelected) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(allItemIds)
                            }
                        }) {
                            Icon(
                                imageVector = if (isAllSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = if (isAllSelected) "取消全选" else "全选"
                            )
                        }
                        Text(
                            text = if (isAllSelected) "取消全选" else "全选",
                            style = MaterialTheme.typography.labelMedium
                        )

                        Spacer(Modifier.weight(1f))

                        // 批量导出
                        Box {
                            IconButton(onClick = { batchExportMenu = true }) {
                                Icon(Icons.Default.Download, contentDescription = "导出")
                            }
                            RoundDropdownMenu(
                                expanded = batchExportMenu,
                                onDismissRequest = { batchExportMenu = false }
                            ) {
                                RoundDropdownMenuItem(
                                    text = "导出 JSON",
                                    onClick = {
                                        batchExportMenu = false
                                        pendingExportIsMd = false
                                        pendingExportList = selectedBookmarks
                                        exportLauncher.launch(null)
                                    }
                                )
                                RoundDropdownMenuItem(
                                    text = "导出 Markdown",
                                    onClick = {
                                        batchExportMenu = false
                                        pendingExportIsMd = true
                                        pendingExportList = selectedBookmarks
                                        exportLauncher.launch(null)
                                    }
                                )
                            }
                        }
                        Text(
                            text = "导出",
                            style = MaterialTheme.typography.labelMedium
                        )

                        Spacer(Modifier.weight(1f))

                        // 批量删除
                        IconButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = if (selectedIds.isNotEmpty())
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selectedIds.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = { viewModel.syncFromWebDav() },
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isSyncing,
                    modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        .padding(top = paddingValues.calculateTopPadding())
                )
            }
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = contentState,
                label = "bookmarkTransition"
            ) { state ->
                when (state) {
                    "LOADING" -> EmptyMessage(
                        message = "加载中...",
                        isLoading = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding(), bottom = 120.dp)
                    )
                    "EMPTY" -> EmptyMessage(
                        message = "没有书签！",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding(), bottom = 120.dp)
                    )
                    "CONTENT" -> Box(modifier = Modifier.fillMaxSize()) {
                        FastScrollLazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = adaptiveContentPadding(
                                top = paddingValues.calculateTopPadding(),
                                bottom = paddingValues.calculateBottomPadding() + 8.dp
                            )
                        ) {
                            items(
                                items = bookmarkGroups,
                                key = { it.header.toString() }
                            ) { groupData ->
                                val isCollapsed =
                                    collapsedGroups.contains(groupData.header.toString())
                                // 分组内所有 id
                                val groupItemIds = groupData.items.map { it.id }
                                val groupSelected = groupItemIds.filter { selectedIds.contains(it) }
                                val isGroupAllSelected = groupItemIds.isNotEmpty() && groupSelected.size == groupItemIds.size

                                GlassCard(
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    cornerRadius = 16.dp,
                                    containerColor = LegadoTheme.colorScheme.surfaceContainer
                                ) {
                                    BookmarkGroupCard(
                                        groupData = groupData,
                                        isCollapsed = isCollapsed,
                                        isMultiSelectMode = isMultiSelectMode,
                                        isGroupAllSelected = isGroupAllSelected,
                                        onToggle = {
                                            if (isMultiSelectMode) {
                                                // 多选模式下点击分组头 = 切换该组全选
                                                if (isGroupAllSelected) {
                                                    selectedIds.removeAll(groupItemIds.toSet())
                                                } else {
                                                    groupItemIds.forEach { id ->
                                                        if (!selectedIds.contains(id)) selectedIds.add(id)
                                                    }
                                                }
                                            } else {
                                                viewModel.toggleGroupCollapse(groupData.header)
                                            }
                                        },
                                        onLongClick = {
                                            if (!isMultiSelectMode) {
                                                groupMenuHeader = groupData
                                                showGroupMenu = true
                                            }
                                        }
                                    )

                                    AnimatedVisibility(
                                        visible = !isCollapsed && groupData.items.isNotEmpty()
                                    ) {
                                        Column {
                                            HorizontalDivider(
                                                color = LegadoTheme.colorScheme.surface
                                            )
                                            groupData.items.forEach { bookmarkUi ->
                                                val isSelected = selectedIds.contains(bookmarkUi.id)
                                                BookmarkItem(
                                                    bookmark = bookmarkUi.rawBookmark,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .then(
                                                            if (isSelected)
                                                                Modifier.background(
                                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                                                )
                                                            else Modifier
                                                        ),
                                                    isDur = false,
                                                    onClick = {
                                                        if (isMultiSelectMode) {
                                                            if (isSelected) selectedIds.remove(bookmarkUi.id)
                                                            else selectedIds.add(bookmarkUi.id)
                                                        } else {
                                                            editingBookmark = bookmarkUi.rawBookmark
                                                            showBottomSheet = true
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!isMultiSelectMode) {
                                                            selectedIds.add(bookmarkUi.id)
                                                        } else {
                                                            if (isSelected) selectedIds.remove(bookmarkUi.id)
                                                            else selectedIds.add(bookmarkUi.id)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TopFloatingStickyItem(
                            item = stickyGroup,
                            modifier = Modifier.padding(
                                top = paddingValues.calculateTopPadding() + 4.dp,
                                start = 8.dp
                            )
                        ) { group ->
                            TextCard(
                                text = group.bookName,
                                textStyle = LegadoTheme.typography.labelLarge,
                                backgroundColor = LegadoTheme.colorScheme.cardContainer,
                                contentColor = LegadoTheme.colorScheme.onCardContainer,
                                cornerRadius = 8.dp,
                                horizontalPadding = 8.dp,
                                verticalPadding = 6.dp,
                                onClick = {
                                    scope.launch {
                                        val index =
                                            bookmarkGroups.indexOfFirst { it.header == group }
                                        if (index >= 0) listState.animateScrollToItem(index)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } // end Column

        // 分组长按菜单（合并 + 导出）
        val currentGroupMenu = groupMenuHeader
        Box(modifier = Modifier.fillMaxSize()) {
            RoundDropdownMenu(
                expanded = showGroupMenu && currentGroupMenu != null,
                onDismissRequest = { showGroupMenu = false; groupMenuHeader = null }
            ) {
                RoundDropdownMenuItem(
                    text = "合并同名书籍书签",
                    onClick = {
                        showGroupMenu = false
                        val header = currentGroupMenu?.header
                        groupMenuHeader = null
                        if (header != null) mergeTargetHeader = header
                    }
                )
                RoundDropdownMenuItem(
                    text = "导出本书签 JSON",
                    onClick = {
                        showGroupMenu = false
                        val items = currentGroupMenu?.items?.map { it.rawBookmark }
                        groupMenuHeader = null
                        if (!items.isNullOrEmpty()) {
                            pendingExportIsMd = false
                            pendingExportList = items
                            exportLauncher.launch(null)
                        }
                    }
                )
                RoundDropdownMenuItem(
                    text = "导出本书签 Markdown",
                    onClick = {
                        showGroupMenu = false
                        val items = currentGroupMenu?.items?.map { it.rawBookmark }
                        groupMenuHeader = null
                        if (!items.isNullOrEmpty()) {
                            pendingExportIsMd = true
                            pendingExportList = items
                            exportLauncher.launch(null)
                        }
                    }
                )
            }
        }

        // 合并书签对话框
        if (mergeTargetHeader != null && mergeCandidates.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { mergeTargetHeader = null },
                title = { Text("合并书签到「${mergeTargetHeader!!.bookAuthor}」") },
                text = {
                    Column {
                        Text(
                            text = "选择要合并进来的版本（书签将归入当前作者名下）：",
                            style = LegadoTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        mergeCandidates.forEach { author ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = mergeSelected.contains(author),
                                    onCheckedChange = { checked ->
                                        if (checked) mergeSelected.add(author)
                                        else mergeSelected.remove(author)
                                    }
                                )
                                Text(text = author, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = mergeTargetHeader!!
                            mergeSelected.toList().forEach { sourceAuthor ->
                                viewModel.mergeBookmarksInto(target, sourceAuthor)
                            }
                            mergeTargetHeader = null
                        },
                        enabled = mergeSelected.isNotEmpty()
                    ) { Text("合并") }
                },
                dismissButton = {
                    TextButton(onClick = { mergeTargetHeader = null }) { Text("取消") }
                }
            )
        }

        BookmarkEditSheet(
            show = showBottomSheet && editingBookmark != null,
            bookmark = editingBookmark ?: Bookmark(),
            onDismiss = {
                showBottomSheet = false
                editingBookmark = null
            },
            onSave = { updatedBookmark ->
                viewModel.updateBookmark(updatedBookmark)
                showBottomSheet = false
            },
            onDelete = { bookmarkToDelete ->
                viewModel.deleteBookmark(bookmarkToDelete)
                showBottomSheet = false
            }
        )
        } // end PullToRefreshBox
    }
}

/** 参考图风格的书籍分组卡片头 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkGroupCard(
    groupData: BookmarkGroupUiData,
    isCollapsed: Boolean,
    isMultiSelectMode: Boolean,
    isGroupAllSelected: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit
) {
    val isMiuix = ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)
    val accentColor = if (isMiuix)
        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选模式显示分组复选框
        if (isMultiSelectMode) {
            Icon(
                imageVector = if (isGroupAllSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 4.dp)
            )
        }

        // 左侧内容区
        Column(modifier = Modifier.weight(1f)) {
            // 笔记数量大字
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = LegadoTheme.colorScheme.onSurface
                        )
                    ) { append("${groupData.bookmarkCount}") }
                    withStyle(
                        SpanStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = LegadoTheme.colorScheme.onSurfaceVariant
                        )
                    ) { append(" 条笔记") }
                }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 书名
            AppText(
                text = groupData.header.bookName,
                style = LegadoTheme.typography.titleMedium,
                color = accentColor,
                maxLines = 1
            )

            // 作者（若有）
            if (groupData.header.bookAuthor.isNotBlank()) {
                AppText(
                    text = groupData.header.bookAuthor,
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // 备注（若有）—— 彩色圆角矩形底
            if (!groupData.remark.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    AppText(
                        text = groupData.remark,
                        style = LegadoTheme.typography.labelSmall,
                        color = accentColor,
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 阅读时长 + 进度行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (groupData.readTimeMs > 0L) {
                    AppText(
                        text = "⏱ ${formatReadDuration(groupData.readTimeMs)}",
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (groupData.progressFraction >= 0f) {
                    AppText(
                        text = "▐ ${(groupData.progressFraction * 100).roundToInt()}%",
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 进度条
            if (groupData.progressFraction >= 0f) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { groupData.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accentColor,
                    trackColor = accentColor.copy(alpha = 0.15f)
                )
            }
        }

        // 右侧分组标签列
        if (groupData.groupNames.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                groupData.groupNames.forEach { groupName ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accentColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        AppText(
                            text = groupName,
                            style = LegadoTheme.typography.labelSmall,
                            color = accentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
