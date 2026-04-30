package io.legado.app.ui.book.note

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReadNote
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.bookmark.NoteEditSheet
import io.legado.app.ui.widget.components.button.TopBarActionButton
import io.legado.app.ui.widget.components.button.TopBarNavigationButton
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.list.TopFloatingStickyItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import org.koin.androidx.compose.koinViewModel

@OptIn(
    ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun AllNoteScreen(
    viewModel: AllNoteViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val contentState = when {
        uiState.isLoading -> "LOADING"
        uiState.notes.isEmpty() -> "EMPTY"
        else -> "CONTENT"
    }
    val searchText = uiState.searchQuery
    val collapsedGroups = uiState.collapsedGroups
    val notesGrouped = uiState.notes
    val noteGroups = remember(notesGrouped) { notesGrouped.entries.toList() }
    val allKeys = notesGrouped.keys
    val isAllCollapsed =
        allKeys.isNotEmpty() && allKeys.all { collapsedGroups.contains(it.toString()) }
    val listState = rememberLazyListState()
    var showSearch by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<ReadNote?>(null) }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    val stickyGroup by remember(noteGroups, collapsedGroups, listState) {
        derivedStateOf {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleGroup = noteGroups.getOrNull(firstVisibleIndex)
                ?: return@derivedStateOf null
            val isCollapsed = collapsedGroups.contains(firstVisibleGroup.key.toString())
            val shouldStick = firstVisibleIndex > 0 || listState.firstVisibleItemScrollOffset > 24
            if (!isCollapsed && shouldStick) firstVisibleGroup.key else null
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                GlassMediumFlexibleTopAppBar(
                    title = "所有笔记",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        TopBarNavigationButton(onClick = onBack)
                    },
                    actions = {
                        if (notesGrouped.isNotEmpty()) {
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
                    }
                )
                AnimatedVisibility(visible = showSearch) {
                    SearchBar(
                        query = searchText,
                        onQueryChange = { viewModel.onSearchQueryChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .adaptiveHorizontalPadding()
                            .padding(bottom = 8.dp),
                        placeholder = "搜索笔记..."
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(targetState = contentState, label = "NoteContent") { state ->
                when (state) {
                    "EMPTY" -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyMessage(message = "暂无笔记")
                    }

                    "CONTENT" -> FastScrollLazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = adaptiveContentPadding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding()
                        )
                    ) {
                        noteGroups.forEachIndexed { groupIndex, (header, items) ->
                            val isCollapsed = collapsedGroups.contains(header.toString())

                            item(key = "header_${header}") {
                                NoteGroupHeader(
                                    header = header,
                                    isCollapsed = isCollapsed,
                                    onToggle = { viewModel.toggleGroupCollapse(header) },
                                    modifier = Modifier.animateItem()
                                )
                            }

                            if (!isCollapsed) {
                                items(
                                    items = items,
                                    key = { "note_${it.id}" }
                                ) { item ->
                                    NoteListItem(
                                        item = item,
                                        modifier = Modifier.animateItem(),
                                        onClick = { editingNote = item.rawNote },
                                        onLongClick = { editingNote = item.rawNote }
                                    )
                                    if (items.indexOf(item) < items.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = LegadoTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> Box(modifier = Modifier.fillMaxSize())
                }
            }

            TopFloatingStickyItem(
                item = stickyGroup,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = padding.calculateTopPadding() + 4.dp, start = 8.dp)
            ) { group ->
                TextCard(
                    text = "${group.bookName} · ${group.bookAuthor}",
                    textStyle = LegadoTheme.typography.labelLarge,
                    backgroundColor = LegadoTheme.colorScheme.cardContainer,
                    contentColor = LegadoTheme.colorScheme.onCardContainer,
                    cornerRadius = 8.dp,
                    horizontalPadding = 8.dp,
                    verticalPadding = 6.dp,
                    onClick = {}
                )
            }
        }

        val noteForSheet = editingNote ?: remember(editingNote == null) { ReadNote() }
        NoteEditSheet(
            show = editingNote != null,
            note = noteForSheet,
            onDismiss = { editingNote = null },
            onSave = { updated ->
                viewModel.updateNote(updated)
                editingNote = null
            },
            onDelete = { toDelete ->
                viewModel.deleteNote(toDelete)
                editingNote = null
            }
        )
    }
}

@Composable
private fun NoteGroupHeader(
    header: NoteGroupHeader,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isCollapsed)
            LegadoTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else Color.Transparent,
        label = "NoteGroupBg"
    )
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle),
        headlineContent = {
            AppText(
                text = header.bookName,
                style = LegadoTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = if (header.bookAuthor.isNotEmpty()) {
            {
                AppText(
                    text = header.bookAuthor,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant
                )
            }
        } else null,
        trailingContent = {
            AppText(
                text = if (isCollapsed) "展开" else "收起",
                style = MaterialTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.primary
            )
        },
        colors = ListItemDefaults.colors(containerColor = bgColor)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteListItem(
    item: NoteItemUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            AppText(
                text = item.chapterName,
                style = LegadoTheme.typography.bodyMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (item.selectedText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    AppText(
                        text = item.selectedText,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.noteContent.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    AppText(
                        text = item.noteContent,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
