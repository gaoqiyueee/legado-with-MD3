package io.legado.app.ui.book.bookmark

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.AppWebDav
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BookmarkGroupHeader(
    val bookName: String,
    val bookAuthor: String
) {
    override fun toString(): String = "$bookName|$bookAuthor"
}

@Immutable
data class BookmarkItemUi(
    val id: Long,
    val content: String,
    val chapterName: String,
    val bookText: String,
    val bookName: String,
    val bookAuthor: String,
    val rawBookmark: Bookmark
)

/** 每个书籍分组的展示数据 */
@Immutable
data class BookmarkGroupUiData(
    val header: BookmarkGroupHeader,
    val bookmarkCount: Int,
    val coverUrl: String?,
    /** 总阅读时长，毫秒 */
    val readTimeMs: Long,
    /** 阅读进度 0f..1f，-1f 表示无数据 */
    val progressFraction: Float,
    /** 用户添加的备注（可能为空） */
    val remark: String?,
    val items: List<BookmarkItemUi>
)

@Immutable
data class BookmarkUiState(
    val isLoading: Boolean = false,
    val groups: List<BookmarkGroupUiData> = emptyList(),
    val error: Throwable? = null,
    val searchQuery: String = "",
    val collapsedGroups: Set<String> = emptySet()
) {
    val bookmarks: Map<BookmarkGroupHeader, List<BookmarkItemUi>>
        get() = groups.associate { it.header to it.items }
}

class AllBookmarkViewModel(
    application: Application,
    private val bookmarkDao: BookmarkDao,
    private val bookDao: BookDao,
    private val readRecordDao: ReadRecordDao
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<BookmarkUiState> = combine(
        _searchQuery,
        _collapsedGroups,
        bookmarkDao.flowAll()
    ) { query, collapsed, allBookmarks ->

        // 批量查询，避免 N+1
        val bookMap = bookDao.all.associateBy { it.name to it.author }
        val readRecordMap = readRecordDao.all.associateBy { it.bookName to it.bookAuthor }

        val filteredList = if (query.isBlank()) {
            allBookmarks
        } else {
            allBookmarks.filter {
                it.bookName.contains(query, ignoreCase = true) ||
                        it.bookAuthor.contains(query, ignoreCase = true) ||
                        it.chapterName.contains(query, ignoreCase = true) ||
                        it.bookText.contains(query, ignoreCase = true) ||
                        it.content.contains(query, ignoreCase = true) ||
                        (!bookMap[it.bookName to it.bookAuthor]?.remark.isNullOrBlank() &&
                                bookMap[it.bookName to it.bookAuthor]!!.remark!!.contains(query, ignoreCase = true))
            }
        }

        val groups = filteredList
            .groupBy { BookmarkGroupHeader(it.bookName, it.bookAuthor) }
            .map { (header, rawBookmarks) ->
                val book = bookMap[header.bookName to header.bookAuthor]
                val readRecord = readRecordMap[header.bookName to header.bookAuthor]

                val progressFraction = if (book != null && book.totalChapterNum > 0) {
                    (book.durChapterIndex.toFloat() / book.totalChapterNum).coerceIn(0f, 1f)
                } else {
                    -1f
                }

                BookmarkGroupUiData(
                    header = header,
                    bookmarkCount = rawBookmarks.size,
                    coverUrl = book?.getDisplayCover(),
                    readTimeMs = readRecord?.readTime ?: 0L,
                    progressFraction = progressFraction,
                    remark = book?.remark?.takeIf { it.isNotBlank() },
                    items = rawBookmarks.map { bm ->
                        BookmarkItemUi(
                            id = bm.time,
                            content = bm.content,
                            chapterName = bm.chapterName,
                            bookText = bm.bookText,
                            bookName = bm.bookName,
                            bookAuthor = bm.bookAuthor,
                            rawBookmark = bm
                        )
                    }
                )
            }

        BookmarkUiState(
            isLoading = false,
            groups = groups,
            searchQuery = query,
            collapsedGroups = collapsed
        )
    }.catch { e ->
        emit(BookmarkUiState(isLoading = false, error = e))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BookmarkUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleGroupCollapse(groupKey: BookmarkGroupHeader) {
        val stringKey = groupKey.toString()
        _collapsedGroups.update { current ->
            if (current.contains(stringKey)) current - stringKey else current + stringKey
        }
    }

    fun toggleAllCollapse(currentKeys: Set<BookmarkGroupHeader>) {
        val stringKeys = currentKeys.map { it.toString() }.toSet()
        _collapsedGroups.update { current ->
            if (current.containsAll(stringKeys) && stringKeys.isNotEmpty()) {
                emptySet()
            } else {
                stringKeys
            }
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.insert(bookmark)
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.delete(bookmark)
            AppWebDav.uploadBookmarks()
        }
    }

    suspend fun getMergeCandidates(header: BookmarkGroupHeader): List<String> {
        return withContext(Dispatchers.IO) {
            bookmarkDao.getMergeCandidateAuthors(header.bookName, header.bookAuthor)
        }
    }

    fun mergeBookmarksInto(target: BookmarkGroupHeader, sourceAuthor: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sourceBookmarks = bookmarkDao.getByBook(target.bookName, sourceAuthor)
            sourceBookmarks.forEach { bm ->
                bookmarkDao.insert(bm.copy(bookAuthor = target.bookAuthor))
            }
            bookmarkDao.deleteByBook(target.bookName, sourceAuthor)
        }
    }

    fun exportBookmark(treeUri: Uri, isMarkdown: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                val suffix = if (isMarkdown) "md" else "json"
                val fileName = "bookmark-${dateFormat.format(Date())}.$suffix"

                val dirDoc = FileDoc.fromUri(treeUri, true)
                val fileDoc = dirDoc.createFileIfNotExist(fileName)

                fileDoc.openOutputStream().getOrThrow().use { outputStream ->
                    val allData = bookmarkDao.all
                    if (isMarkdown) {
                        writeMarkdown(outputStream, allData)
                    } else {
                        GSON.writeToOutputStream(outputStream, allData)
                    }
                }

                withContext(Dispatchers.Main) {
                    context.toastOnUi("导出成功: $fileName")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    getApplication<Application>().toastOnUi("导出失败: ${e.message}")
                }
            }
        }
    }

    private fun writeMarkdown(outputStream: java.io.OutputStream, bookmarks: List<Bookmark>) {
        val sb = StringBuilder()
        var lastHeader = ""
        bookmarks.forEach {
            val currentHeader = "${it.bookName}|${it.bookAuthor}"
            if (currentHeader != lastHeader) {
                lastHeader = currentHeader
                sb.append("\n## ${it.bookName} - ${it.bookAuthor}\n\n")
            }
            sb.append("#### ${it.chapterName}\n")
            sb.append("> **原文：** ${it.bookText}\n\n")
            sb.append("${it.content}\n\n")
            sb.append("---\n")
        }
        outputStream.write(sb.toString().toByteArray())
    }
}
