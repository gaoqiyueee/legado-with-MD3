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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Bookmark
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
    // 合并对话框状态
    var mergeTargetHeader by remember { mutableStateOf<BookmarkGroupHeader?>(null) }
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportBookmark(it, pendingExportIsMd)
            Toast.makeText(context, "开始导出...", Toast.LENGTH_SHORT).show()
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                GlassMediumFlexibleTopAppBar(
                    title = "所有书签",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                    actions = {
                        if (bookmarkGroups.isNotEmpty()) {
                            TopBarActionButton(
                                onClick = { viewModel.toggleAllCollapse(allKeys) },
                                imageVector = if (isAllCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                                contentDescription = null
                            )
                        }
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
                                    exportLauncher.launch(null)
                                }
                            )
                            RoundDropdownMenuItem(
                                text = "导出 Markdown",
                                onClick = {
                                    showMenu = false
                                    pendingExportIsMd = true
                                    exportLauncher.launch(null)
                                }
                            )
                        }
                    }
                )
                AnimatedVisibility(
                    modifier = Modifier.adaptiveHorizontalPadding(),
                    visible = showSearch,
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
        }
    ) { paddingValues ->
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
                                bottom = 120.dp
                            )
                        ) {
                            items(
                                items = bookmarkGroups,
                                key = { it.header.toString() }
                            ) { groupData ->
                                val isCollapsed =
                                    collapsedGroups.contains(groupData.header.toString())

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
                                        onToggle = { viewModel.toggleGroupCollapse(groupData.header) },
                                        onLongClick = { mergeTargetHeader = groupData.header }
                                    )

                                    AnimatedVisibility(
                                        visible = !isCollapsed && groupData.items.isNotEmpty()
                                    ) {
                                        Column {
                                            HorizontalDivider(
                                                color = LegadoTheme.colorScheme.surface
                                            )
                                            groupData.items.forEach { bookmarkUi ->
                                                BookmarkItem(
                                                    bookmark = bookmarkUi.rawBookmark,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    isDur = false,
                                                    onClick = {
                                                        editingBookmark = bookmarkUi.rawBookmark
                                                        showBottomSheet = true
                                                    },
                                                    onLongClick = {
                                                        editingBookmark = bookmarkUi.rawBookmark
                                                        showBottomSheet = true
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
    }
}

/** 参考图风格的书籍分组卡片头 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkGroupCard(
    groupData: BookmarkGroupUiData,
    isCollapsed: Boolean,
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

            // 备注（若有）
            if (!groupData.remark.isNullOrBlank()) {
                AppText(
                    text = groupData.remark,
                    style = LegadoTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    maxLines = 2
                )
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
    }
}
