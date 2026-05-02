package io.legado.app.ui.book.readRecord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.hutool.core.date.DateUtil
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.help.AppWebDav
import io.legado.app.help.UploadState
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

data class AlmostFinishedBook(
    val bookName: String,
    val bookAuthor: String,
    val coverUrl: String?,
    val progressFraction: Float
)

data class ReadRecordUiState(
    val isLoading: Boolean = true,
    val totalReadTime: Long = 0,
    val totalBookmarkCount: Int = 0,
    val groupedRecords: Map<String, List<ReadRecordDetail>> = emptyMap(),
    val timelineRecords: Map<String, List<ReadRecordSession>> = emptyMap(),
    val latestRecords: List<ReadRecord> = emptyList(),
    val selectedDate: LocalDate? = null,
    val searchKey: String? = null,
    val dailyReadCounts: Map<LocalDate, Int> = emptyMap(),
    val dailyReadTimes: Map<LocalDate, Long> = emptyMap(),
    val viewPeriod: ViewPeriod = ViewPeriod.DAY,
    // Dashboard data (shown below LATEST mode)
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val last7DaysReadTime: List<Pair<String, Long>> = emptyList(),
    val almostFinishedBooks: List<AlmostFinishedBook> = emptyList()
)

enum class ViewPeriod {
    DAY,
    WEEK,
    MONTH,
    YEAR
}

enum class DisplayMode {
    AGGREGATE,
    TIMELINE,
    LATEST
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadRecordViewModel(
    private val repository: ReadRecordRepository,
    private val bookRepository: BookRepository,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao
) : ViewModel() {

    init {
        viewModelScope.launch {
            repository.autoMergeByBookUrl()
        }
    }

    private val _displayMode = MutableStateFlow(DisplayMode.AGGREGATE)
    val displayMode = _displayMode.asStateFlow()
    private val _searchKey = MutableStateFlow("")
    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val _viewPeriod = MutableStateFlow(ViewPeriod.DAY)
    val viewPeriod = _viewPeriod.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _uploadState = MutableStateFlow(UploadState.IDLE)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loadedDataFlow = _searchKey
        .flatMapLatest { query ->
            combine(
                repository.getAllRecordDetails(query),
                repository.getLatestReadRecords(query),
                repository.getAllSessions(),
                repository.getTotalReadTime(),
                bookmarkDao.flowAll()
            ) { details, latest, sessions, totalTime, bookmarks ->
                val bookmarkCount = bookmarks.size
                // Compute dashboard data on IO dispatcher
                val (currentStreak, bestStreak) = computeStreaks(details)
                val last7Days = computeLast7DaysReadTime(sessions)
                val almostFinished = computeAlmostFinishedBooks(latest)
                LoadedData(totalTime, details, latest, sessions, bookmarkCount, currentStreak, bestStreak, last7Days, almostFinished)
            }
        }

    val uiState: StateFlow<ReadRecordUiState> = combine(
        loadedDataFlow,
        _selectedDate,
        _searchKey,
        _viewPeriod
    ) { data, selectedDate, searchKey, viewPeriod ->
        val dateStr = selectedDate?.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val dailyCounts = data.details
            .groupBy { it.date }
            .mapKeys { LocalDate.parse(it.key, DateTimeFormatter.ISO_LOCAL_DATE) }
            .mapValues { it.value.size }

        val dailyTimes = data.sessions
            .groupBy { DateUtil.format(Date(it.startTime), "yyyy-MM-dd") }
            .mapKeys { LocalDate.parse(it.key, DateTimeFormatter.ISO_LOCAL_DATE) }
            .mapValues { (_, sessions) ->
                sessions.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
            }

        val filteredDetails = data.details.filter { detail ->
            dateStr == null || detail.date == dateStr
        }

        // 根据视图周期聚合记录
        val aggregatedRecords = when (viewPeriod) {
            ViewPeriod.DAY -> filteredDetails.groupBy { it.date }
            ViewPeriod.WEEK -> groupByWeek(filteredDetails)
            ViewPeriod.MONTH -> groupByMonth(filteredDetails)
            ViewPeriod.YEAR -> groupByYear(filteredDetails)
        }

        val timelineMap = data.sessions
            .asSequence()
            .filter { session ->
                val sDate = DateUtil.format(Date(session.startTime), "yyyy-MM-dd")
                (dateStr == null || sDate == dateStr) &&
                        (searchKey.isEmpty() ||
                                session.bookName.contains(searchKey, ignoreCase = true) ||
                                session.bookAuthor.contains(searchKey, ignoreCase = true))
            }
            .groupBy { DateUtil.format(Date(it.startTime), "yyyy-MM-dd") }
            .mapValues { (_, sessions) ->
                mergeContinuousSessions(sessions).reversed()
            }
            .toSortedMap(compareByDescending { it })

        ReadRecordUiState(
            isLoading = false,
            totalReadTime = data.totalReadTime,
            totalBookmarkCount = data.bookmarkCount,
            groupedRecords = aggregatedRecords,
            timelineRecords = timelineMap,
            latestRecords = data.latestRecords,
            selectedDate = selectedDate,
            searchKey = searchKey,
            dailyReadCounts = dailyCounts,
            dailyReadTimes = dailyTimes,
            viewPeriod = viewPeriod,
            currentStreak = data.currentStreak,
            bestStreak = data.bestStreak,
            last7DaysReadTime = data.last7DaysReadTime,
            almostFinishedBooks = data.almostFinishedBooks
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadRecordUiState(isLoading = true)
    )

    fun setSearchKey(query: String) {
        _searchKey.value = query
    }

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
    }

    fun setViewPeriod(period: ViewPeriod) {
        _viewPeriod.value = period
    }

    fun setSelectedDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    fun deleteDetail(detail: ReadRecordDetail) {
        viewModelScope.launch {
            repository.deleteDetail(detail)
            AppWebDav.markReadRecordDirty()
            kotlin.runCatching { AppWebDav.uploadReadRecords() }
        }
    }

    fun deleteSession(session: ReadRecordSession) {
        viewModelScope.launch {
            repository.deleteSession(session)
            AppWebDav.markReadRecordDirty()
            kotlin.runCatching { AppWebDav.uploadReadRecords() }
        }
    }

    fun deleteReadRecord(record: ReadRecord) {
        viewModelScope.launch {
            repository.deleteReadRecord(record)
            AppWebDav.markReadRecordDirty()
            kotlin.runCatching { AppWebDav.uploadReadRecords() }
        }
    }

    private fun mergeContinuousSessions(sessions: List<ReadRecordSession>): List<ReadRecordSession> {
        if (sessions.isEmpty()) return emptyList()
        val mergedList = mutableListOf<ReadRecordSession>()
        mergedList.add(sessions.first().copy())

        val gapLimit = 20 * 60 * 1000L

        for (i in 1 until sessions.size) {
            val current = sessions[i]
            val last = mergedList.last()
            if (current.bookName == last.bookName &&
                current.bookAuthor == last.bookAuthor &&
                (current.startTime - last.endTime) <= gapLimit
            ) {
                mergedList[mergedList.lastIndex] = last.copy(endTime = current.endTime)
            } else {
                mergedList.add(current.copy())
            }
        }
        return mergedList
    }

    suspend fun getChapterTitle(bookName: String, bookAuthor: String, chapterIndexLong: Long): String? {
        return bookRepository.getChapterTitle(bookName, bookAuthor, chapterIndexLong.toInt())
    }

    suspend fun getBookCover(bookName: String, bookAuthor: String): String? {
        return bookRepository.getBookCoverByNameAndAuthor(bookName, bookAuthor)
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return repository.getMergeCandidates(targetRecord)
    }

    fun mergeReadRecords(targetRecord: ReadRecord, sourceRecords: List<ReadRecord>) {
        if (sourceRecords.isEmpty()) return
        viewModelScope.launch {
            repository.mergeReadRecordInto(targetRecord, sourceRecords)
            AppWebDav.markReadRecordDirty()
            kotlin.runCatching { AppWebDav.uploadReadRecords() }
        }
    }

    /**
     * 手动从 WebDAV 拉取最新数据（阅读时间、书签、笔记），并重新执行合并去重。
     * 用于下拉刷新。
     */
    fun syncFromWebDav() {
        if (_isRefreshing.value) return
        if (!AppWebDav.isOk) return
        viewModelScope.launch {
            _isRefreshing.value = true
            withContext(Dispatchers.IO) {
                if (AppConfig.syncBookProgress || AppConfig.syncBookProgressPlus) {
                    // 先下载远端最新（避免上传时覆盖其他设备的数据），再上传合并结果
                    kotlin.runCatching { AppWebDav.downloadReadRecords() }
                    kotlin.runCatching { AppWebDav.downloadBookmarks() }
                    kotlin.runCatching { AppWebDav.downloadMarkers() }
                    kotlin.runCatching { AppWebDav.downloadNotes() }
                    AppWebDav.markBookmarkDirty()
                    AppWebDav.markReadRecordDirty()
                    kotlin.runCatching { AppWebDav.uploadBookmarks(force = true) }
                    kotlin.runCatching { AppWebDav.uploadReadRecords(force = true) }
                    // 下载后重新合并去重
                    kotlin.runCatching { repository.autoMergeByBookUrl() }
                }
            }
            _isRefreshing.value = false
        }
    }

    /**
     * 强制上传：以本地数据覆盖云端，同时通过 uploadState 反馈进度（上传中/完成/失败）
     */
    fun uploadToWebDav() {
        if (!AppWebDav.isOk) return
        if (_uploadState.value == UploadState.UPLOADING) return
        viewModelScope.launch(Dispatchers.IO) {
            _uploadState.value = UploadState.UPLOADING
            AppWebDav.markReadRecordDirty()
            val result = kotlin.runCatching { AppWebDav.uploadReadRecords(force = true) }
            _uploadState.value = if (result.isSuccess) UploadState.SUCCESS else UploadState.FAILURE
            kotlinx.coroutines.delay(2000)
            _uploadState.value = UploadState.IDLE
        }
    }

    /**
     * 按周聚合阅读记录
     */
    private fun groupByWeek(details: List<ReadRecordDetail>): Map<String, List<ReadRecordDetail>> {
        return details.groupBy { detail ->
            val date = LocalDate.parse(detail.date, DateTimeFormatter.ISO_LOCAL_DATE)
            val yearWeek = "${date.year}-W${date.get(java.time.temporal.WeekFields.ISO.weekOfYear())}"
            yearWeek
        }.toSortedMap(compareByDescending { it })
    }

    /**
     * 按月聚合阅读记录
     */
    private fun groupByMonth(details: List<ReadRecordDetail>): Map<String, List<ReadRecordDetail>> {
        return details.groupBy { detail ->
            detail.date.substring(0, 7) // "yyyy-MM"
        }.toSortedMap(compareByDescending { it })
    }

    /**
     * 按年聚合阅读记录
     */
    private fun groupByYear(details: List<ReadRecordDetail>): Map<String, List<ReadRecordDetail>> {
        return details.groupBy { detail ->
            detail.date.substring(0, 4) // "yyyy"
        }.toSortedMap(compareByDescending { it })
    }

    private data class LoadedData(
        val totalReadTime: Long,
        val details: List<ReadRecordDetail>,
        val latestRecords: List<ReadRecord>,
        val sessions: List<ReadRecordSession>,
        val bookmarkCount: Int,
        val currentStreak: Int = 0,
        val bestStreak: Int = 0,
        val last7DaysReadTime: List<Pair<String, Long>> = emptyList(),
        val almostFinishedBooks: List<AlmostFinishedBook> = emptyList()
    )

    /** Compute current and best reading streak (consecutive days with any reading). */
    private fun computeStreaks(details: List<ReadRecordDetail>): Pair<Int, Int> {
        val allDates = details
            .map { it.date }
            .toSortedSet()
            .mapNotNull { runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull() }
            .sorted()

        if (allDates.isEmpty()) return 0 to 0

        var best = 1
        var current = 1
        for (i in 1 until allDates.size) {
            if (allDates[i] == allDates[i - 1].plusDays(1)) {
                current++
                if (current > best) best = current
            } else {
                current = 1
            }
        }

        // Current streak: count back from today
        val today = LocalDate.now()
        val dateSet = allDates.toHashSet()
        var streak = 0
        var day = today
        while (dateSet.contains(day)) {
            streak++
            day = day.minusDays(1)
        }
        // Also allow yesterday as "current" (user hasn't read today yet)
        if (streak == 0) {
            day = today.minusDays(1)
            while (dateSet.contains(day)) {
                streak++
                day = day.minusDays(1)
            }
        }

        return streak to best
    }

    /** Compute reading time for last 7 days including today. Returns list of (dateLabel, millis). */
    private fun computeLast7DaysReadTime(sessions: List<ReadRecordSession>): List<Pair<String, Long>> {
        val today = LocalDate.now()
        val timeByDate = mutableMapOf<LocalDate, Long>()
        sessions.forEach { session ->
            val date = runCatching {
                LocalDate.parse(
                    DateUtil.format(Date(session.startTime), "yyyy-MM-dd"),
                    DateTimeFormatter.ISO_LOCAL_DATE
                )
            }.getOrNull() ?: return@forEach
            val duration = (session.endTime - session.startTime).coerceAtLeast(0L)
            timeByDate[date] = (timeByDate[date] ?: 0L) + duration
        }
        return (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val label = "${date.monthValue}/${date.dayOfMonth}"
            label to (timeByDate[date] ?: 0L)
        }
    }

    /** Get books with reading progress >= 80% from the book shelf. */
    private suspend fun computeAlmostFinishedBooks(latestRecords: List<ReadRecord>): List<AlmostFinishedBook> {
        return withContext(Dispatchers.IO) {
            latestRecords.mapNotNull { record ->
                val book = bookDao.getBook(record.bookName, record.bookAuthor) ?: return@mapNotNull null
                if (book.totalChapterNum <= 0) return@mapNotNull null
                val progress = book.durChapterIndex.toFloat() / book.totalChapterNum
                if (progress < 0.8f) return@mapNotNull null
                AlmostFinishedBook(
                    bookName = record.bookName,
                    bookAuthor = record.bookAuthor,
                    coverUrl = book.getDisplayCover(),
                    progressFraction = progress.coerceIn(0f, 1f)
                )
            }.sortedByDescending { it.progressFraction }
        }
    }
}
