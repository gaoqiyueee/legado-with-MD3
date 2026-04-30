package io.legado.app.ui.main.bookshelf

import kotlinx.coroutines.flow.flow
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import com.google.gson.stream.JsonWriter
import io.legado.app.R
import io.legado.app.base.BaseRuleEvent
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.BookStorageState
import io.legado.app.help.AppWebDav
import io.legado.app.model.localBook.LocalBook
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.data.repository.UploadRepository
import io.legado.app.domain.usecase.BatchCacheDownloadUseCase
import io.legado.app.domain.usecase.UpdateBooksGroupUseCase
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isUpError
import io.legado.app.help.book.removeType
import io.legado.app.help.book.sync
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.CacheBook
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.ui.config.bookshelfConfig.BookshelfConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.eventBus.FlowEventBus
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class BookshelfViewModel(
    application: Application,
    private val bookGroupRepository: BookGroupRepository,
    private val uploadRepository: UploadRepository,
    private val batchCacheDownloadUseCase: BatchCacheDownloadUseCase,
    private val updateBooksGroupUseCase: UpdateBooksGroupUseCase
) : BaseViewModel(application) {
    var addBookJob: Coroutine<*>? = null

    private val groupIdFlow = MutableStateFlow(BookshelfConfig.saveTabPosition)
    private val searchKeyFlow = MutableStateFlow("")
    private val searchModeFlow = MutableStateFlow(false)
    private val refreshTrigger = MutableStateFlow(0)
    private val loadingTextFlow = MutableStateFlow<String?>(null)

    // 更新相关
    private var threadCount = AppConfig.threadCount
    private var poolSize = min(threadCount, AppConst.MAX_THREAD)
    private var upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    private val waitUpTocBooks = LinkedList<String>()
    private val onUpTocBooks = ConcurrentHashMap.newKeySet<String>()
    private val updatingBooksFlow = MutableStateFlow<Set<String>>(emptySet())
    private val upBooksCountFlow = MutableStateFlow(0)
    private var upTocJob: Job? = null
    private var cacheBookJob: Job? = null
    private val eventListenerSource = ConcurrentHashMap<BookSource, Boolean>()

    val scrollTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    protected val _eventChannel = Channel<BaseRuleEvent>()
    val events = _eventChannel.receiveAsFlow()

    val groupsFlow: StateFlow<List<BookGroup>> = bookGroupRepository.flowShow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allGroupsFlow: StateFlow<List<BookGroup>> = bookGroupRepository.flowAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allBooksFlow = appDb.bookDao.flowBookShelf()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private data class GroupPreviewState(
        val previews: Map<Long, List<BookShelfItem>>,
        val counts: Map<Long, Int>,
        val allBookCount: Int
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val booksFlow = combine(groupIdFlow, refreshTrigger) { groupId, _ -> groupId }
        .flatMapLatest { groupId ->
            appDb.bookDao.flowBookShelfByGroup(groupId).map { list ->
                // 仅"全部"分组显示元信息/归档书籍，其他分组只显示本地已下载书籍
                val filtered = if (groupId == BookGroup.IdAll) list
                               else list.filter { it.storageState == BookStorageState.LOCAL }
                sortBooks(
                    filtered,
                    groupsFlow.value.find { it.groupId == groupId })
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private val groupPreviewsFlow =
        combine(groupsFlow, allBooksFlow, refreshTrigger) { groups, allBooks, _ ->
            if (BookshelfConfig.bookGroupStyle in 2..3) {
                val previews = HashMap<Long, List<BookShelfItem>>(groups.size)
                val counts = HashMap<Long, Int>(groups.size)
                groups.forEach { group ->
                    val groupBooks = when (group.groupId) {
                        BookGroup.IdRoot -> {
                            val sumUserGroupIds =
                                groups.filter { it.groupId > 0 }.sumOf { it.groupId }
                            allBooks.filter { book ->
                                (book.type and BookType.text) > 0 &&
                                        (book.type and BookType.local) == 0 &&
                                        (sumUserGroupIds and book.group) == 0L
                            }
                        }

                        BookGroup.IdAll -> allBooks
                        BookGroup.IdLocal -> allBooks.filter { (it.type and BookType.local) > 0 }
                        BookGroup.IdAudio -> allBooks.filter { (it.type and BookType.audio) > 0 }
                        BookGroup.IdNetNone -> {
                            val sumUserGroupIds =
                                groups.filter { it.groupId > 0 }.sumOf { it.groupId }
                            allBooks.filter { book ->
                                (book.type and BookType.audio) == 0 &&
                                        (book.type and BookType.local) == 0 &&
                                        (sumUserGroupIds and book.group) == 0L
                            }
                        }

                        BookGroup.IdLocalNone -> {
                            val sumUserGroupIds =
                                groups.filter { it.groupId > 0 }.sumOf { it.groupId }
                            allBooks.filter { book ->
                                (book.type and BookType.local) > 0 &&
                                        (sumUserGroupIds and book.group) == 0L
                            }
                        }

                        BookGroup.IdManga -> allBooks.filter { (it.type and BookType.image) > 0 }
                        BookGroup.IdText -> allBooks.filter { (it.type and BookType.text) > 0 }
                        BookGroup.IdError -> allBooks.filter { (it.type and BookType.updateError) > 0 }
                        BookGroup.IdUnread -> allBooks.filter { it.durChapterIndex == 0 && it.durChapterPos == 0 }
                        BookGroup.IdReading -> allBooks.filter { it.totalChapterNum > 0 && it.durChapterIndex > 0 && it.durChapterIndex < it.totalChapterNum - 1 }
                        BookGroup.IdReadFinished -> allBooks.filter { it.totalChapterNum > 0 && it.durChapterIndex >= it.totalChapterNum - 1 }
                        else -> allBooks.filter { (it.group and group.groupId) != 0L }
                    }
                    counts[group.groupId] = groupBooks.size
                    val sortedBooks = sortBooks(groupBooks, group)
                    val booksWithCover = sortedBooks.filter { it.getDisplayCover() != null }
                    val result = if (booksWithCover.size >= 4) {
                        booksWithCover.take(4)
                    } else {
                        (booksWithCover + sortedBooks.filter { it.getDisplayCover() == null }).take(
                            4
                        )
                    }
                    previews[group.groupId] = result
                }
                GroupPreviewState(previews, counts, allBooks.size)
            } else {
                GroupPreviewState(emptyMap(), emptyMap(), allBooks.size)
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private val coreInternalStateFlow = combine(
        groupIdFlow,
        searchKeyFlow,
        searchModeFlow,
        loadingTextFlow,
        updatingBooksFlow
    ) { groupId, searchKey, isSearchMode, loadingText, updatingBooks ->
        InternalState(groupId, searchKey, isSearchMode, loadingText, updatingBooks, 0)
    }

    private val internalStateFlow = combine(
        coreInternalStateFlow,
        upBooksCountFlow
    ) { core, upBooksCount ->
        core.copy(upBooksCount = upBooksCount)
    }

    data class InternalState(
        val groupId: Long,
        val searchKey: String,
        val isSearchMode: Boolean,
        val loadingText: String?,
        val updatingBooks: Set<String>,
        val upBooksCount: Int
    )

    val uiState: StateFlow<BookshelfUiState> = combine(
        booksFlow,
        groupsFlow,
        allGroupsFlow,
        groupPreviewsFlow,
        internalStateFlow
    ) { books, groups, allGroups, previews, internal ->
        val filteredBooks = if (!internal.isSearchMode || internal.searchKey.isBlank()) {
            books
        } else {
            books.filter { it.matchesSearchKey(internal.searchKey) }
        }

        BookshelfUiState(
            items = filteredBooks,
            groups = groups,
            allGroups = allGroups,
            groupPreviews = previews.previews,
            groupBookCounts = previews.counts,
            currentGroupBookCount = books.size,
            allBooksCount = previews.allBookCount,
            selectedGroupIndex = groups.indexOfFirst { it.groupId == internal.groupId }
                .coerceAtLeast(0),
            selectedGroupId = internal.groupId,
            searchKey = internal.searchKey,
            isSearch = internal.isSearchMode,
            isLoading = internal.loadingText != null,
            loadingText = internal.loadingText,
            upBooksCount = internal.upBooksCount,
            updatingBooks = internal.updatingBooks
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())

    init {
        viewModelScope.launch {
            FlowEventBus.with<Unit>(EventBus.UP_ALL_BOOK_TOC).collect {
                upAllBookToc()
            }
        }
        viewModelScope.launch {
            FlowEventBus.with<Unit>(EventBus.BOOKSHELF_REFRESH).collect {
                refresh()
            }
        }

        // 监听排序配置变化，触发刷新
        viewModelScope.launch {
            snapshotFlow { BookshelfConfig.bookshelfSort }.collect { refresh() }
        }
        viewModelScope.launch {
            snapshotFlow { BookshelfConfig.bookshelfSortOrder }.collect { refresh() }
        }
        viewModelScope.launch {
            snapshotFlow { BookshelfConfig.bookGroupStyle }.collect { refresh() }
        }
        viewModelScope.launch {
            snapshotFlow { BookshelfConfig.showWaitUpCount }.collect { postUpBooksCount() }
        }

        if (BookshelfConfig.autoRefreshBook) {
            upAllBookToc()
        }
    }

    override fun onCleared() {
        super.onCleared()
        upTocPool.close()
    }

    private fun sortBooks(list: List<BookShelfItem>, group: BookGroup?): List<BookShelfItem> {
        val bookSort = group?.getRealBookSort() ?: BookshelfConfig.bookshelfSort
        val isDescending = BookshelfConfig.bookshelfSortOrder == 1

        return when (bookSort) {
            1 -> if (isDescending) list.sortedByDescending { it.latestChapterTime }
            else list.sortedBy { it.latestChapterTime }

            2 -> if (isDescending)
                list.sortedWith { o1, o2 -> o2.name.cnCompare(o1.name) }
            else
                list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }

            3 -> if (isDescending) list.sortedByDescending { it.order }
            else list.sortedBy { it.order }

            4 -> if (isDescending) list.sortedByDescending {
                max(
                    it.latestChapterTime,
                    it.durChapterTime
                )
            }
            else list.sortedBy { max(it.latestChapterTime, it.durChapterTime) }

            5 -> if (isDescending)
                list.sortedWith { o1, o2 -> o2.author.cnCompare(o1.author) }
            else
                list.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }

            else -> if (isDescending) list.sortedByDescending { it.durChapterTime }
            else list.sortedBy { it.durChapterTime }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getBooksFlow(groupId: Long): Flow<List<BookShelfItem>> {
        return combine(
            appDb.bookDao.flowBookShelfByGroup(groupId),
            searchKeyFlow,
            searchModeFlow,
            groupsFlow,
            refreshTrigger
        ) { books, searchKey, isSearchMode, groups, _ ->
            val group = groups.find { it.groupId == groupId }
            val storageFiltered = if (groupId == BookGroup.IdAll) books
                                  else books.filter { it.storageState == BookStorageState.LOCAL }
            val filtered = if (!isSearchMode || searchKey.isBlank()) {
                storageFiltered
            } else {
                storageFiltered.filter { it.matchesSearchKey(searchKey) }
            }
            sortBooks(filtered, group)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }

    fun changeGroup(groupId: Long) {
        if (groupIdFlow.value != groupId) {
            groupIdFlow.value = groupId
            BookshelfConfig.saveTabPosition = groupId
        }
    }

    fun setSearchKey(key: String) {
        searchKeyFlow.value = key
    }

    fun setSearchMode(active: Boolean) {
        searchModeFlow.value = active
        if (!active) {
            searchKeyFlow.value = ""
        }
    }

    fun refresh() {
        refreshTrigger.value++
    }

    fun moveBooksToGroup(bookUrls: Set<String>, groupId: Long) {
        if (bookUrls.isEmpty()) return
        execute {
            updateBooksGroupUseCase.replaceGroup(bookUrls, groupId)
        }.onError {
            context.toastOnUi("更新分组失败\n${it.localizedMessage}")
        }
    }

    fun saveBookOrder(reorderedBooks: List<BookShelfItem>) {
        if (reorderedBooks.isEmpty()) return
        val isDescending = BookshelfConfig.bookshelfSortOrder == 1
        val maxOrder = reorderedBooks.size
        execute {
            val updates = reorderedBooks.mapIndexedNotNull { index, book ->
                appDb.bookDao.getBook(book.bookUrl)?.apply {
                    order = if (isDescending) maxOrder - index else index + 1
                }
            }
            if (updates.isNotEmpty()) {
                appDb.bookDao.update(*updates.toTypedArray())
            }
        }.onError {
            context.toastOnUi("排序保存失败\n${it.localizedMessage}")
        }
    }

    fun downloadBooks(bookUrls: Set<String>, downloadAllChapters: Boolean = false) {
        if (bookUrls.isEmpty()) return
        execute {
            batchCacheDownloadUseCase.execute(
                bookUrls = bookUrls,
                downloadAllChapters = downloadAllChapters,
                skipAudioBooks = true
            )
        }.onSuccess { count ->
            if (count > 0) {
                context.toastOnUi("已加入缓存队列: $count 本")
            } else {
                context.toastOnUi(R.string.no_download)
            }
        }.onError {
            context.toastOnUi("批量缓存失败\n${it.localizedMessage}")
        }
    }

    fun gotoTop() {
        scrollTrigger.tryEmit(Unit)
    }

    // 更新逻辑移入
    fun upAllBookToc() {
        execute {
            addToWaitUp(appDb.bookDao.hasUpdateBooks)
        }
    }

    fun upToc(books: List<BookShelfItem>) {
        execute(context = upTocPool) {
            val bookUrls = books.filter { !it.isLocal && it.canUpdate }.map { it.bookUrl }
            val fullBooks = bookUrls.mapNotNull { appDb.bookDao.getBook(it) }
            addToWaitUp(fullBooks)
        }
        // 同步 WebDAV 数据（书签、阅读时长双向合并；阅读进度比较后询问）
        if (AppWebDav.isOk && (AppConfig.syncBookProgress || AppConfig.syncBookProgressPlus)) {
            execute {
                kotlin.runCatching { AppWebDav.uploadBookmarks() }
                kotlin.runCatching { AppWebDav.downloadBookmarks() }
                kotlin.runCatching { AppWebDav.uploadReadRecords() }
                kotlin.runCatching { AppWebDav.downloadReadRecords() }
            }
        }
    }

    @Synchronized
    private fun addToWaitUp(books: List<Book>) {
        books.forEach { book ->
            if (!waitUpTocBooks.contains(book.bookUrl) && !onUpTocBooks.contains(book.bookUrl)) {
                waitUpTocBooks.add(book.bookUrl)
            }
        }
        postUpBooksCount()
        if (upTocJob == null) {
            startUpTocJob()
        }
    }

    private fun startUpTocJob() {
        upPool()
        postUpBooksCount()
        upTocJob = viewModelScope.launch(upTocPool) {
            flow {
                while (true) {
                    emit(waitUpTocBooks.poll() ?: break)
                }
            }.onEachParallel(threadCount) {
                onUpTocBooks.add(it)
                updatingBooksFlow.value = onUpTocBooks.toSet()
                postEvent(EventBus.UP_BOOKSHELF, it)
                updateToc(it)
            }.onEach {
                onUpTocBooks.remove(it)
                updatingBooksFlow.value = onUpTocBooks.toSet()
                postEvent(EventBus.UP_BOOKSHELF, it)
                postUpBooksCount()
            }.onCompletion {
                upTocJob = null
                if (waitUpTocBooks.isNotEmpty()) {
                    startUpTocJob()
                }
                if (it == null && cacheBookJob == null && !CacheBookService.isRun) {
                    cacheBook()
                }
            }.catch {
                AppLog.put("更新目录出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private fun upPool() {
        threadCount = AppConfig.threadCount
        val newPoolSize = min(threadCount, AppConst.MAX_THREAD)
        if (poolSize == newPoolSize) return
        poolSize = newPoolSize
        upTocPool.close()
        upTocPool = Executors.newFixedThreadPool(poolSize).asCoroutineDispatcher()
    }

    private suspend fun updateToc(bookUrl: String) {
        val book = appDb.bookDao.getBook(bookUrl) ?: return
        val source = appDb.bookSourceDao.getBookSource(book.origin)
        if (source == null) {
            if (!book.isUpError) {
                book.addType(BookType.updateError)
                appDb.bookDao.update(book)
            }
            return
        }
        if (source.eventListener) {
            if (eventListenerSource.putIfAbsent(source, true) == null) {
                SourceCallBack.callBackSource(
                    viewModelScope,
                    SourceCallBack.START_SHELF_REFRESH,
                    source
                )
            }
        }
        kotlin.runCatching {
            val oldBook = book.copy()
            if (book.tocUrl.isBlank()) {
                WebBook.getBookInfoAwait(source, book)
            } else {
                WebBook.runPreUpdateJs(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            book.sync(oldBook)
            book.removeType(BookType.updateError)
            if (book.bookUrl == bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
                BookHelp.updateCacheFolder(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(bookUrl)
            appDb.bookChapterDao.insert(*toc.toTypedArray())
            ReadBook.onChapterListUpdated(book)
            addDownload(source, book)
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("${book.name} 更新目录失败\n${it.localizedMessage}", it)
            appDb.bookDao.getBook(book.bookUrl)?.let { book ->
                book.addType(BookType.updateError)
                appDb.bookDao.update(book)
            }
        }
    }

    private fun postUpBooksCount() {
        val count =
            if (BookshelfConfig.showWaitUpCount) waitUpTocBooks.size + onUpTocBooks.size else 0
        upBooksCountFlow.value = count
    }

    private fun addDownload(source: BookSource, book: Book) {
        if (AppConfig.preDownloadNum == 0) return
        val endIndex =
            min(book.totalChapterNum - 1, book.durChapterIndex + AppConfig.preDownloadNum)
        val cacheBook = CacheBook.getOrCreate(source, book)
        cacheBook.addDownload(book.durChapterIndex, endIndex)
    }

    private fun cacheBook() {
        eventListenerSource.toList().forEach {
            SourceCallBack.callBackSource(
                viewModelScope,
                SourceCallBack.END_SHELF_REFRESH,
                it.first
            )
        }
        eventListenerSource.clear()
        if (AppConfig.preDownloadNum == 0) return
        cacheBookJob?.cancel()
        cacheBookJob = viewModelScope.launch(upTocPool) {
            launch {
                while (isActive && CacheBook.isRun) {
                    CacheBook.setWorkingState(waitUpTocBooks.isEmpty() && onUpTocBooks.isEmpty())
                    delay(1000)
                }
            }
            CacheBook.startProcessJob(upTocPool)
        }
    }

    fun addBookByUrl(bookUrls: String) {
        var successCount = 0
        loadingTextFlow.value = "添加中..."
        addBookJob = execute {
            val hasBookUrlPattern: List<BookSourcePart> by lazy {
                appDb.bookSourceDao.hasBookUrlPattern
            }
            val urls = bookUrls.split("\n")
            for (url in urls) {
                val bookUrl = url.trim()
                if (bookUrl.isEmpty()) continue
                if (appDb.bookDao.getBook(bookUrl) != null) {
                    successCount++
                    continue
                }
                val baseUrl = NetworkUtils.getBaseUrl(bookUrl) ?: continue
                var source = appDb.bookSourceDao.getBookSourceAddBook(baseUrl)
                if (source == null) {
                    for (bookSource in hasBookUrlPattern) {
                        try {
                            val bs = bookSource.getBookSource()!!
                            if (bookUrl.matches(bs.bookUrlPattern!!.toRegex())) {
                                source = bs
                                break
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                val bookSource = source ?: continue
                val book = Book(
                    bookUrl = bookUrl,
                    origin = bookSource.bookSourceUrl,
                    originName = bookSource.bookSourceName
                )
                kotlin.runCatching {
                    WebBook.getBookInfoAwait(bookSource, book)
                }.onSuccess {
                    val dbBook = appDb.bookDao.getBook(it.name, it.author)
                    if (dbBook != null) {
                        val toc = WebBook.getChapterListAwait(bookSource, it).getOrThrow()
                        dbBook.migrateTo(it, toc)
                        appDb.bookDao.insert(it)
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    } else {
                        it.order = appDb.bookDao.minOrder - 1
                        it.save()
                    }
                    successCount++
                    loadingTextFlow.value = "添加中... ($successCount)"
                }
            }
        }.onSuccess {
            if (successCount > 0) {
                context.toastOnUi(R.string.success)
            } else {
                context.toastOnUi("添加网址失败")
            }
        }.onError {
            AppLog.put("添加网址出错\n${it.localizedMessage}", it, true)
        }.onFinally {
            loadingTextFlow.value = null
        }
    }

    fun exportToUri(uri: Uri, items: List<BookShelfItem>) {
        execute {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                val writer = JsonWriter(OutputStreamWriter(out, "UTF-8"))
                writer.setIndent("  ")
                writer.beginArray()
                items.forEach {
                    val bookMap = hashMapOf<String, String?>()
                    bookMap["name"] = it.name
                    bookMap["author"] = it.author
                    // intro is not in BookShelfItem, fetch from DB if needed or skip
                    // For now, let's keep it simple and skip intro or fetch it
                    val fullBook = appDb.bookDao.getBook(it.bookUrl)
                    bookMap["intro"] = fullBook?.getDisplayIntro()
                    GSON.toJson(bookMap, bookMap::class.java, writer)
                }
                writer.endArray()
                writer.close()
            }
        }.onSuccess {
            _eventChannel.trySend(BaseRuleEvent.ShowSnackbar("导出成功"))
        }.onError {
            _eventChannel.trySend(BaseRuleEvent.ShowSnackbar("导出失败\n${it.localizedMessage}"))
        }
    }

    fun uploadBookshelf(items: List<BookShelfItem>) {
        execute {
            val json = withContext(Dispatchers.Default) {
                val list = items.map {
                    val bookMap = hashMapOf<String, String?>()
                    bookMap["name"] = it.name
                    bookMap["author"] = it.author
                    val fullBook = appDb.bookDao.getBook(it.bookUrl)
                    bookMap["intro"] = fullBook?.getDisplayIntro()
                    bookMap
                }
                GSON.toJson(list)
            }
            uploadRepository.upload(
                fileName = "bookshelf.json",
                file = json,
                contentType = "application/json"
            )
        }.onSuccess { url ->
            _eventChannel.trySend(
                BaseRuleEvent.ShowSnackbar(
                    message = "上传成功: $url",
                    actionLabel = "复制链接",
                    url = url
                )
            )
        }.onError {
            _eventChannel.trySend(
                BaseRuleEvent.ShowSnackbar(
                    message = "上传失败: ${it.localizedMessage}"
                )
            )
        }
    }

    fun exportBookshelf(items: List<BookShelfItem>?, success: (file: File) -> Unit) {
        execute {
            items?.let {
                val path = "${context.filesDir}/books.json"
                FileUtils.delete(path)
                val file = FileUtils.createFileWithReplace(path)
                FileOutputStream(file).use { out ->
                    val writer = JsonWriter(OutputStreamWriter(out, "UTF-8"))
                    writer.setIndent("  ")
                    writer.beginArray()
                    items.forEach {
                        val bookMap = hashMapOf<String, String?>()
                        bookMap["name"] = it.name
                        bookMap["author"] = it.author
                        val fullBook = appDb.bookDao.getBook(it.bookUrl)
                        bookMap["intro"] = fullBook?.getDisplayIntro()
                        GSON.toJson(bookMap, bookMap::class.java, writer)
                    }
                    writer.endArray()
                    writer.close()
                }
                file
            } ?: throw NoStackTraceException("书籍不能为空")
        }.onSuccess {
            success(it)
        }.onError {
            context.toastOnUi("导出书籍出错\n${it.localizedMessage}")
        }
    }

    fun importBookshelf(str: String, groupId: Long) {
        execute {
            val text = str.trim()
            when {
                text.isAbsUrl() -> {
                    okHttpClient.newCallResponseBody {
                        url(text)
                    }.decompressed().text().let {
                        importBookshelf(it, groupId)
                    }
                }

                text.isJsonArray() -> {
                    importBookshelfByJson(text, groupId)
                }

                else -> {
                    throw NoStackTraceException("格式不对")
                }
            }
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }

    private fun importBookshelfByJson(json: String, groupId: Long) {
        loadingTextFlow.value = "导入中..."
        execute {
            val bookSourceParts = appDb.bookSourceDao.allEnabledPart
            val semaphore = Semaphore(AppConfig.threadCount)
            GSON.fromJsonArray<Map<String, String?>>(json).getOrThrow().forEach { bookInfo ->
                val name = bookInfo["name"] ?: ""
                val author = bookInfo["author"] ?: ""
                if (name.isEmpty() || appDb.bookDao.has(name, author)) {
                    return@forEach
                }
                semaphore.withPermit {
                    WebBook.preciseSearch(
                        this, bookSourceParts, name, author,
                        semaphore = semaphore
                    ).onSuccess {
                        val book = it.first
                        if (groupId > 0) {
                            book.group = groupId
                        }
                        book.save()
                    }.onError { e ->
                        context.toastOnUi(e.localizedMessage)
                    }
                }
            }
        }.onError {
            it.printOnDebug()
        }.onFinally {
            loadingTextFlow.value = null
            context.toastOnUi(R.string.success)
        }
    }

    private fun BookShelfItem.matchesSearchKey(searchKey: String): Boolean {
        return name.contains(searchKey, true) ||
                author.contains(searchKey, true) ||
                (!remark.isNullOrBlank() && remark.contains(searchKey, true))
    }

    // ─── WebDAV 元信息书籍管理 ─────────────────────────────────────────────────

    /**
     * 扫描 WebDAV /books/ 目录，把尚未在书架上的书文件生成 METADATA_ONLY 条目。
     * 触发方式：书架菜单 → "从 WebDAV 扫描书籍"。
     */
    fun scanWebDavBooks(onFinish: (added: Int) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val webDav = AppWebDav.defaultBookWebDav ?: run {
                withContext(Dispatchers.Main) { onFinish(0) }
                return@launch
            }
            val remoteList = kotlin.runCatching {
                webDav.getRemoteBookList(webDav.rootBookUrl)
            }.getOrNull() ?: run {
                withContext(Dispatchers.Main) { onFinish(0) }
                return@launch
            }
            var added = 0
            remoteList.forEach { remoteBook ->
                // 已在书架上（按文件名判断）则跳过
                if (appDb.bookDao.getBookByFileName(remoteBook.filename) != null) return@forEach
                val book = Book(
                    bookUrl = remoteBook.path,
                    origin = BookType.webDavTag + remoteBook.path,
                    originName = remoteBook.filename,
                    name = remoteBook.filename.substringBeforeLast("."),
                    author = "",
                    type = BookType.text,
                    storageState = BookStorageState.METADATA_ONLY
                )
                appDb.bookDao.insert(book)
                added++
            }
            withContext(Dispatchers.Main) { onFinish(added) }
        }
    }

    /**
     * 从 WebDAV 下载 METADATA_ONLY 或 ARCHIVED 书籍到本地。
     * 下载成功后创建新的 LOCAL 书籍条目，并删除原元信息条目。
     */
    fun downloadMetadataBook(book: Book, onResult: (success: Boolean, msg: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            kotlin.runCatching {
                // 从 DB 重取完整 Book，确保 origin 字段存在（toLightBook() 不含 origin）
                val fullBook = appDb.bookDao.getBook(book.bookUrl)
                    ?: error("书籍不存在")
                val webDav = AppWebDav.defaultBookWebDav
                    ?: error("WebDAV 未配置")
                val remoteUrl = fullBook.getRemoteUrl()
                    ?: error("此书没有 WebDAV 远程地址")
                val remoteBook = webDav.getRemoteBook(remoteUrl)
                    ?: error("WebDAV 上找不到该书文件")
                val localUri = webDav.downloadRemoteBook(remoteBook)
                val newBooks = LocalBook.importFiles(localUri)
                val newBook = newBooks.firstOrNull() ?: error("书籍导入失败")
                // 将用户自定义信息迁移到新条目
                // 同时保留 origin（webDavTag + remoteUrl），确保归档选项可用
                // name/author 由 importFile() 从文件元数据提取（analyzeNameAuthor + upBookInfo），
                // 不做覆盖：首次下载时取 EPUB 内准确元数据；归档循环时同一文件提取结果与原来一致
                newBook.apply {
                    origin = fullBook.origin
                    group = fullBook.group
                    order = fullBook.order
                    customCoverUrl = fullBook.customCoverUrl
                    customIntro = fullBook.customIntro
                    remark = fullBook.remark
                    // 阅读进度：恢复上次读到的章节和位置
                    durChapterIndex = fullBook.durChapterIndex
                    durChapterPos = fullBook.durChapterPos
                    durChapterTime = fullBook.durChapterTime
                    durChapterTitle = fullBook.durChapterTitle
                    storageState = BookStorageState.LOCAL
                    save()
                }
                // 仅当新条目与旧条目 bookUrl 不同时才删除旧条目，
                // 避免 importFile() 复用同 bookUrl 对象时自删
                if (newBook.bookUrl != fullBook.bookUrl) {
                    appDb.bookDao.delete(fullBook)
                }
                withContext(Dispatchers.Main) { onResult(true, "下载完成") }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "下载失败") }
            }
        }
    }

    /**
     * 归档 LOCAL 书籍：在 WebDAV 上已有备份时，将本地条目转换为 METADATA_ONLY 云端条目，
     * 删除本地文件。归档后书籍与"扫描云端书籍"得到的条目完全一致，再次点击走相同下载流程。
     */
    fun archiveBook(book: Book, onResult: (success: Boolean, msg: String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            kotlin.runCatching {
                val remoteUrl = book.getRemoteUrl()
                    ?: error("此书未备份到 WebDAV，无法归档")
                // 创建与扫描云端书籍完全相同格式的 METADATA_ONLY 条目
                val cloudBook = Book(
                    bookUrl = remoteUrl,
                    origin = book.origin,
                    originName = book.originName,
                    name = book.name,
                    author = book.author,
                    coverUrl = book.coverUrl,
                    customCoverUrl = book.customCoverUrl,
                    intro = book.intro,
                    customIntro = book.customIntro,
                    remark = book.remark,
                    group = book.group,
                    order = book.order,
                    type = BookType.text,
                    storageState = BookStorageState.METADATA_ONLY,
                    durChapterIndex = book.durChapterIndex,
                    durChapterPos = book.durChapterPos,
                    durChapterTime = book.durChapterTime,
                    durChapterTitle = book.durChapterTitle,
                )
                appDb.bookDao.insert(cloudBook)
                LocalBook.deleteBook(book, deleteOriginal = true)
                appDb.bookDao.delete(book)
                withContext(Dispatchers.Main) { onResult(true, "已归档") }
            }.onFailure { e ->
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "归档失败") }
            }
        }
    }

}
