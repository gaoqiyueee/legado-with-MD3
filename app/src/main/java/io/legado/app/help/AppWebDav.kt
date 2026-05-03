package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadMarker
import io.legado.app.data.entities.ReadNote
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.toastOnUi
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 同步事件，供 UI 层订阅展示 Toast
 */
sealed class SyncEvent {
    /** 正在同步（下载/上传进行中） */
    object Syncing : SyncEvent()
    /** 同步完成（数据有变化，已合并） */
    object Success : SyncEvent()
    /** 已是最新，无需同步 */
    object NoChange : SyncEvent()
    /** 节流跳过（10 秒内已同步过，稍后再试） */
    object Throttled : SyncEvent()
    /** 同步失败 */
    data class Failure(val message: String) : SyncEvent()
    /** 检测到云端与本地存在差异，等待用户选择处理方式 */
    data class Conflict(
        val bookmarksDiffer: Boolean,
        val markersDiffer: Boolean,
        val cloudBookmarkCount: Int,
        val localBookmarkCount: Int,
        val cloudMarkerCount: Int,
        val localMarkerCount: Int
    ) : SyncEvent()
}

/** 冲突解决方式 */
enum class ConflictChoice {
    /** 合并（取两端并集，推荐） */
    MERGE,
    /** 以云端为准，覆盖本地 */
    USE_CLOUD,
    /** 保留本地，上传覆盖云端 */
    KEEP_LOCAL
}

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"
    private val notesWebDavUrl get() = "${rootWebDavUrl}readNotes/"
    private val bookmarksWebDavUrl get() = "${rootWebDavUrl}bookmarks/"
    private val readRecordsWebDavUrl get() = "${rootWebDavUrl}readRecords/"
    private val bookInfoWebDavUrl get() = "${rootWebDavUrl}bookInfo/"
    private val markersWebDavUrl get() = "${rootWebDavUrl}markers/"
    private val bookGroupsWebDavUrl get() = "${rootWebDavUrl}bookGroups/"

    /** 本地书签是否有未同步到云端的变更 */
    @Volatile
    var bookmarkDirty: Boolean = false

    /** 标记本地书签已变更，需要在下次 onPause/close 时上传 */
    fun markBookmarkDirty() {
        bookmarkDirty = true
    }

    /** 本地位置标记是否有未同步到云端的变更 */
    @Volatile
    var markerDirty: Boolean = false

    /** 标记本地位置标记已变更 */
    fun markMarkerDirty() {
        markerDirty = true
    }

    /** 本地阅读记录是否有未同步到云端的变更 */
    @Volatile
    var readRecordDirty: Boolean = false

    /** 标记本地阅读记录已变更 */
    fun markReadRecordDirty() {
        readRecordDirty = true
    }

    /**
     * 全局上传节流：同一个 key 的上传请求在 MIN_UPLOAD_INTERVAL_MS 毫秒内只执行一次。
     * 防止多个 Activity 的 onResume 同时触发大量 WebDAV 请求导致限流（HTTP 429）。
     */
    private val lastUploadTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MIN_UPLOAD_INTERVAL_MS = 10_000L   // 同一资源 10 秒内最多上传一次

    /** 若距上次上传不足 [MIN_UPLOAD_INTERVAL_MS]，则跳过本次并保留 dirty=true，返回 false。
     *  force=true 时跳过节流检查（用于用户主动触发的强制上传）。*/
    private fun canUpload(key: String, force: Boolean = false): Boolean {
        if (force) {
            lastUploadTime[key] = System.currentTimeMillis()
            return true
        }
        val now = System.currentTimeMillis()
        val last = lastUploadTime[key] ?: 0L
        if (now - last < MIN_UPLOAD_INTERVAL_MS) return false
        lastUploadTime[key] = now
        return true
    }

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    init {
        runBlocking {
            upConfig()
        }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        kotlin.runCatching {
            authorization = null
            defaultBookWebDav = null
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val mAuthorization = Authorization(account, password)
                checkAuthorization(mAuthorization)
                WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                WebDav(notesWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookmarksWebDavUrl, mAuthorization).makeAsDir()
                WebDav(readRecordsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookInfoWebDavUrl, mAuthorization).makeAsDir()
                WebDav(markersWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookGroupsWebDavUrl, mAuthorization).makeAsDir()
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            //appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            webDav.downloadTo(Backup.zipFilePath, true)
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
            Restore.restoreLocked(Backup.backupPath)
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    suspend fun testWebDav(): Boolean {
        return kotlin.runCatching {
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (account.isNullOrEmpty() || password.isNullOrEmpty()) {
                appCtx.toastOnUi("账号或密码为空")
                return false
            }

            val auth = Authorization(account, password)
            checkAuthorization(auth)

            appCtx.toastOnUi("WebDAV 服务可用")
            true
        }.getOrElse {
            it.printStackTrace()
            if (it !is WebDavException) {
                appCtx.toastOnUi(it.message ?: "未知错误")
            }
            false
        }
    }



    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            WebDav(putUrl, it).upload(Backup.zipFilePath)
        }
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(
        bookProgress: BookProgress,
        onSuccess: (() -> Unit)? = null
    ): Boolean {
        try {
            val authorization = authorization ?: return false
            if (!AppConfig.syncBookProgress) return false
            if (!NetworkUtils.isAvailable()) return false
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
            return true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
            return false
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        return getBookProgress(book.name, book.author)
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(name: String, author: String): BookProgress? {
        val url = getProgressUrl(name, author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()

                }



            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName]
            webDavFile ?: return@forEach  // 修复: 此前 bare return 会退出整个函数
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

    /**
     * 上传所有笔记到 WebDAV
     */
    suspend fun uploadNotes() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val notes = appDb.readNoteDao.all
            if (notes.isEmpty()) return
            val json = GSON.toJson(notes)
            WebDav(notesWebDavUrl, authorization).makeAsDir()
            WebDav("${notesWebDavUrl}notes.json", authorization).upload(
                json.toByteArray(),
                "application/json"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传笔记失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV 下载笔记并合并到本地
     */
    suspend fun downloadNotes() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val byteArray = WebDav("${notesWebDavUrl}notes.json", authorization).download()
            val json = String(byteArray)
            if (!json.isJson()) return
            val remoteNotes = GSON.fromJsonObject<List<ReadNote>>(json).getOrNull() ?: return
            if (remoteNotes.isNotEmpty()) {
                appDb.readNoteDao.insertAll(remoteNotes)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("下载笔记失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 上传书签到 WebDAV
     * 仅在本地有变更（bookmarkDirty=true）时才上传，上传成功后更新本地同步时间戳
     */
    suspend fun uploadBookmarks(force: Boolean = false) {
        if (!bookmarkDirty) return
        if (!canUpload("bookmarks", force)) return
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            // 上传全部书签，不限于本地书架，确保跨设备完整同步
            val bookmarks = appDb.bookmarkDao.all
            if (bookmarks.isEmpty()) return
            val json = GSON.toJson(bookmarks)
            WebDav(bookmarksWebDavUrl, authorization).makeAsDir()
            WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization).upload(
                json.toByteArray(),
                "application/json"
            )
            CacheManager.put("bookmarkSyncTime", System.currentTimeMillis())
            bookmarkDirty = false
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传书签失败\n${it.localizedMessage}", it)
            throw it
        }
    }

    /**
     * 仅从 WebDAV 获取云端书签列表，不写本地数据库。
     * 返回 Pair<list, cloudLastModified>，云端文件不存在或解析失败时返回 null。
     */
    suspend fun fetchRemoteBookmarks(): Pair<List<Bookmark>, Long>? {
        val authorization = authorization ?: return null
        if (!NetworkUtils.isAvailable()) return null
        return kotlin.runCatching {
            val cloudLastModified = WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization)
                .getWebDavFile()?.lastModify ?: 0L
            val json = String(WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization).download())
            if (!json.isJson()) return null
            val type = object : TypeToken<List<Bookmark>>() {}.type
            val list: List<Bookmark> = GSON.fromJson(json, type) ?: return null
            Pair(list, cloudLastModified)
        }.getOrNull()
    }

    /**
     * 仅从 WebDAV 获取云端位置标记列表，不写本地数据库。
     */
    suspend fun fetchRemoteMarkers(): Pair<List<ReadMarker>, Long>? {
        val authorization = authorization ?: return null
        if (!NetworkUtils.isAvailable()) return null
        return kotlin.runCatching {
            val cloudLastModified = WebDav("${markersWebDavUrl}markers.json", authorization)
                .getWebDavFile()?.lastModify ?: 0L
            val json = String(WebDav("${markersWebDavUrl}markers.json", authorization).download())
            if (!json.isJson()) return null
            val type = object : TypeToken<List<ReadMarker>>() {}.type
            val list: List<ReadMarker> = GSON.fromJson(json, type) ?: return null
            Pair(list, cloudLastModified)
        }.getOrNull()
    }

    /**
     * 将已获取的云端书签按指定策略写入本地。
     * MERGE   = 取两端并集（per-item merge）
     * USE_CLOUD = 云端全量覆盖本地
     * KEEP_LOCAL = 保留本地，强制上传到云端
     */
    suspend fun applyBookmarks(
        remote: List<Bookmark>,
        cloudLastModified: Long,
        choice: ConflictChoice
    ) {
        when (choice) {
            ConflictChoice.USE_CLOUD -> {
                appDb.bookmarkDao.deleteAll()
                if (remote.isNotEmpty()) appDb.bookmarkDao.insert(*remote.toTypedArray())
                if (cloudLastModified > 0) CacheManager.put("bookmarkSyncTime", cloudLastModified)
            }
            ConflictChoice.KEEP_LOCAL -> {
                bookmarkDirty = true
                uploadBookmarks(force = true)
            }
            ConflictChoice.MERGE -> {
                val localSyncTime = CacheManager.getLong("bookmarkSyncTime") ?: 0L
                val localByTime = appDb.bookmarkDao.all.associateBy { it.time }
                val remoteByTime = remote.associateBy { it.time }
                remote.forEach { r ->
                    if (!localByTime.containsKey(r.time) && r.time > localSyncTime)
                        appDb.bookmarkDao.insert(r)
                }
                localByTime.forEach { (time, local) ->
                    if (!remoteByTime.containsKey(time) && time <= localSyncTime)
                        appDb.bookmarkDao.delete(local)
                }
                if (cloudLastModified > 0) CacheManager.put("bookmarkSyncTime", cloudLastModified)
                bookmarkDirty = true  // 合并结果需要回推到云端
            }
        }
    }

    /**
     * 将已获取的云端位置标记按指定策略写入本地。
     */
    suspend fun applyMarkers(
        remote: List<ReadMarker>,
        cloudLastModified: Long,
        choice: ConflictChoice
    ) {
        when (choice) {
            ConflictChoice.USE_CLOUD -> {
                appDb.readMarkerDao.deleteAll()
                if (remote.isNotEmpty()) appDb.readMarkerDao.insert(*remote.toTypedArray())
                if (cloudLastModified > 0) CacheManager.put("markerSyncTime", cloudLastModified)
            }
            ConflictChoice.KEEP_LOCAL -> {
                markerDirty = true
                uploadMarkers(force = true)
            }
            ConflictChoice.MERGE -> {
                val localSyncTime = CacheManager.getLong("markerSyncTime") ?: 0L
                val localById = appDb.readMarkerDao.all.associateBy { it.id }
                val remoteById = remote.associateBy { it.id }
                remote.forEach { r ->
                    if (!localById.containsKey(r.id) && r.id > localSyncTime)
                        appDb.readMarkerDao.insert(r)
                }
                localById.forEach { (id, local) ->
                    if (!remoteById.containsKey(id) && id <= localSyncTime)
                        appDb.readMarkerDao.delete(local)
                }
                if (cloudLastModified > 0) CacheManager.put("markerSyncTime", cloudLastModified)
                markerDirty = true
            }
        }
    }

    /**
     * 从 WebDAV 下载书签并合并到本地（per-item merge，不整书覆盖）
     *
     * 合并规则（与 downloadReadRecords 保持一致）：
     * - 云端有、本地无 + 云端 time > lastSyncTime  → 其他设备新增，插入本地
     * - 云端有、本地无 + 云端 time ≤ lastSyncTime  → 本机上次同步后主动删除，跳过（不还原）
     * - 云端有、本地也有（time 相同）              → 保留本地（本地版本视为更新，无需覆盖）
     * - 本地有、云端无 + 本地 time ≤ lastSyncTime  → 其他设备删除，删除本地
     * - 本地有、云端无 + 本地 time > lastSyncTime  → 本机在上次同步后新增，保留
     *
     * 这样两端交替添加书签时不会互相覆盖，删除操作也能跨端传播。
     */
    suspend fun downloadBookmarks() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val cloudLastModified = WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization)
                .getWebDavFile()?.lastModify ?: 0L
            val localSyncTime = CacheManager.getLong("bookmarkSyncTime") ?: 0L
            if (cloudLastModified > 0 && cloudLastModified <= localSyncTime) return

            val byteArray = WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization).download()
            val json = String(byteArray)
            if (!json.isJson()) return
            val type = object : TypeToken<List<Bookmark>>() {}.type
            val remoteBookmarks: List<Bookmark> = GSON.fromJson(json, type) ?: return

            val localByTime = appDb.bookmarkDao.all.associateBy { it.time }
            val remoteByTime = remoteBookmarks.associateBy { it.time }

            // 云端有、本地无：判断是其他设备新增还是本机已删除
            remoteBookmarks.forEach { remote ->
                if (!localByTime.containsKey(remote.time)) {
                    if (remote.time > localSyncTime) {
                        // 其他设备在上次同步后新增，合并进来
                        appDb.bookmarkDao.insert(remote)
                    }
                    // 否则：本机上次同步后主动删除过，不还原
                }
            }

            // 本地有、云端无：判断是其他设备删除还是本机在本次同步后新增
            localByTime.forEach { (time, local) ->
                if (!remoteByTime.containsKey(time)) {
                    if (time <= localSyncTime) {
                        // 上次同步时就存在、现在云端没了 → 其他设备删除，本地同步删除
                        appDb.bookmarkDao.delete(local)
                    }
                    // 否则：本机在上次同步后新增，保留（下次上传时会推到云端）
                }
            }

            if (cloudLastModified > 0) {
                CacheManager.put("bookmarkSyncTime", cloudLastModified)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("下载书签失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 上传位置标记到 WebDAV
     * 仅在本地有变更（markerDirty=true）时才上传
     */
    suspend fun uploadMarkers(force: Boolean = false) {
        if (!markerDirty) return
        if (!canUpload("markers", force)) return
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val markers = appDb.readMarkerDao.all
            val json = GSON.toJson(markers)
            WebDav(markersWebDavUrl, authorization).makeAsDir()
            WebDav("${markersWebDavUrl}markers.json", authorization).upload(
                json.toByteArray(),
                "application/json"
            )
            CacheManager.put("markerSyncTime", System.currentTimeMillis())
            markerDirty = false
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传位置标记失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV 下载位置标记并合并到本地（per-item merge，与 downloadBookmarks 策略一致）
     */
    suspend fun downloadMarkers() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val cloudLastModified = WebDav("${markersWebDavUrl}markers.json", authorization)
                .getWebDavFile()?.lastModify ?: 0L
            val localSyncTime = CacheManager.getLong("markerSyncTime") ?: 0L
            if (cloudLastModified > 0 && cloudLastModified <= localSyncTime) return

            val byteArray = WebDav("${markersWebDavUrl}markers.json", authorization).download()
            val json = String(byteArray)
            if (!json.isJson()) return
            val type = object : TypeToken<List<ReadMarker>>() {}.type
            val remoteMarkers: List<ReadMarker> = GSON.fromJson(json, type) ?: return

            val localById = appDb.readMarkerDao.all.associateBy { it.id }
            val remoteById = remoteMarkers.associateBy { it.id }

            remoteMarkers.forEach { remote ->
                if (!localById.containsKey(remote.id)) {
                    if (remote.id > localSyncTime) {
                        appDb.readMarkerDao.insert(remote)
                    }
                }
            }

            localById.forEach { (id, local) ->
                if (!remoteById.containsKey(id)) {
                    if (id <= localSyncTime) {
                        appDb.readMarkerDao.delete(local)
                    }
                }
            }

            if (cloudLastModified > 0) {
                CacheManager.put("markerSyncTime", cloudLastModified)
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("下载位置标记失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 上传阅读时长记录到 WebDAV
     * 将本地阅读记录全量覆盖上传到 WebDAV，以本地为准。
     * 上传成功后记录同步时间戳，供下载侧判断哪些云端记录是"其他设备新增"而非"本机已删除"。
     */
    suspend fun uploadReadRecords(force: Boolean = false) {
        if (!readRecordDirty) return
        if (!canUpload("readRecords", force)) return
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val records = appDb.readRecordDao.all
            if (records.isEmpty()) return
            val json = GSON.toJson(records)
            WebDav(readRecordsWebDavUrl, authorization).makeAsDir()
            WebDav("${readRecordsWebDavUrl}records.json", authorization).upload(
                json.toByteArray(),
                "application/json"
            )
            // 记录本次上传时间，下载侧凭此区分"其他设备新增"与"本机已删除"
            CacheManager.put("readRecordSyncTime", System.currentTimeMillis())
            readRecordDirty = false
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传阅读记录失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 上传单本书籍的用户自定义信息（书名、作者、简介、备注、自定义封面）到 WebDAV。
     * 文件路径：bookInfo/{name}_{author}.json
     */
    suspend fun uploadBookInfo(book: Book) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val info = mapOf(
                "name" to book.name,
                "author" to book.author,
                "customIntro" to book.customIntro,
                "remark" to book.remark,
                "customCoverUrl" to book.customCoverUrl,
                "customTag" to book.customTag,
                "modifiedTime" to System.currentTimeMillis()
            )
            val json = GSON.toJson(info)
            val fileName = getBookInfoFileName(book.name, book.author)
            WebDav("$bookInfoWebDavUrl$fileName", authorization).upload(
                json.toByteArray(), "application/json"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传书籍信息失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV 下载所有书籍的用户自定义信息，与本地比较后按需更新。
     * 使用 CacheManager 记录每本书的本地同步时间，仅在云端更新时才拉取。
     */
    suspend fun downloadAllBookInfo() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val cloudFiles = WebDav(bookInfoWebDavUrl, authorization).listFiles()
            if (cloudFiles.isEmpty()) return
            val fileMap = cloudFiles.associateBy { it.displayName }
            appDb.bookDao.all.forEach { book ->
                val fileName = getBookInfoFileName(book.name, book.author)
                val cloudFile = fileMap[fileName] ?: return@forEach
                val localSyncTime = CacheManager.getLong("bookInfoSyncTime_${book.name}_${book.author}") ?: 0L
                if (cloudFile.lastModify > 0 && cloudFile.lastModify <= localSyncTime) return@forEach
                kotlin.runCatching {
                    val byteArray = WebDav("$bookInfoWebDavUrl$fileName", authorization).download()
                    val json = String(byteArray)
                    if (!json.isJson()) return@runCatching
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                    val info: Map<String, Any?> = GSON.fromJson(json, type) ?: return@runCatching
                    var changed = false
                    (info["name"] as? String)?.let { if (it.isNotEmpty() && it != book.name) { book.name = it; changed = true } }
                    (info["author"] as? String)?.let { if (it != book.author) { book.author = it; changed = true } }
                    val customIntro = info["customIntro"] as? String
                    if (customIntro != book.customIntro) { book.customIntro = customIntro; changed = true }
                    val remark = info["remark"] as? String
                    if (remark != book.remark) { book.remark = remark; changed = true }
                    val customCoverUrl = info["customCoverUrl"] as? String
                    if (customCoverUrl != book.customCoverUrl) { book.customCoverUrl = customCoverUrl; changed = true }
                    val customTag = info["customTag"] as? String
                    if (customTag != book.customTag) { book.customTag = customTag; changed = true }
                    if (changed) {
                        appDb.bookDao.update(book)
                    }
                    if (cloudFile.lastModify > 0) {
                        CacheManager.put("bookInfoSyncTime_${book.name}_${book.author}", cloudFile.lastModify)
                    }
                }.onFailure {
                    currentCoroutineContext().ensureActive()
                    AppLog.put("下载书籍信息失败 ${book.name}\n${it.localizedMessage}", it)
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("下载书籍信息列表失败\n${it.localizedMessage}", it)
        }
    }

    private fun getBookInfoFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 从 WebDAV 下载阅读时长记录并合并到本地
     * 每条记录按 (deviceId, bookName, bookAuthor) 区分——各设备独立累加自己的时长，
     * 展示层 SUM 所有 deviceId 得到总时长，无需担心重复计算。
     * 不限于本地书架，确保"阅读记录"页面跨端完全一致。
     *
     * 匹配键优先级：
     * - 若 remote.bookUrl 非空且本地有同 (deviceId, bookUrl) 的记录 → 按 bookUrl 匹配（抗改名）
     * - 否则回退到 (deviceId, bookName, bookAuthor)
     */
    suspend fun downloadReadRecords() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val byteArray = WebDav("${readRecordsWebDavUrl}records.json", authorization).download()
            val json = String(byteArray)
            if (!json.isJson()) return
            val type = object : TypeToken<List<ReadRecord>>() {}.type
            val remoteRecords: List<ReadRecord> = GSON.fromJson(json, type) ?: return
            if (remoteRecords.isEmpty()) return

            val localRecords = appDb.readRecordDao.all
            val localByNameKey = localRecords
                .associateBy { Triple(it.deviceId, it.bookName, it.bookAuthor) }
            // 按 (deviceId, bookUrl) 的辅助索引，仅包含 bookUrl 非空的记录
            val localByUrlKey = localRecords
                .filter { it.bookUrl.isNotBlank() }
                .associateBy { it.deviceId to it.bookUrl }

            remoteRecords.forEach { remote ->
                // 优先用 bookUrl 匹配（抗改名），回退到 name+author
                val local = if (remote.bookUrl.isNotBlank()) {
                    localByUrlKey[remote.deviceId to remote.bookUrl]
                        ?: localByNameKey[Triple(remote.deviceId, remote.bookName, remote.bookAuthor)]
                } else {
                    localByNameKey[Triple(remote.deviceId, remote.bookName, remote.bookAuthor)]
                }

                when {
                    // 本地没有该记录：无条件导入（其他设备的记录不依赖 lastSyncTime 判断）
                    local == null ->
                        appDb.readRecordDao.insert(remote)
                    // 本地有该记录但云端阅读时间更长：更新
                    remote.readTime > local.readTime ->
                        appDb.readRecordDao.insert(
                            local.copy(
                                readTime = remote.readTime,
                                lastRead = maxOf(local.lastRead, remote.lastRead)
                            )
                        )
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("下载阅读记录失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 上传所有用户自定义书籍分组到 WebDAV。
     * 文件路径：bookGroups/groups.json
     * 仅同步用户创建的分组（groupId >= 0），系统虚拟分组（负数 id）不上传。
     */
    suspend fun uploadBookGroups(force: Boolean = false) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        if (!canUpload("bookGroups", force)) return
        kotlin.runCatching {
            val userGroups = appDb.bookGroupDao.all.filter { it.groupId >= 0 }
            val json = GSON.toJson(userGroups)
            WebDav(bookGroupsWebDavUrl, authorization).makeAsDir()
            WebDav("${bookGroupsWebDavUrl}groups.json", authorization).upload(
                json.toByteArray(), "application/json"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传书籍分组失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV 下载书籍分组并合并到本地。
     * 合并策略：以 groupId 为主键，云端有而本地无则插入，云端已有则更新 groupName/cover/bookSort/enableRefresh/show/order。
     * 不删除本地已有而云端没有的分组（防止单端删除覆盖另一端）。
     */
    suspend fun downloadBookGroups() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val byteArray = WebDav("${bookGroupsWebDavUrl}groups.json", authorization).download()
            val json = String(byteArray)
            if (!json.isJson()) return
            val type = object : TypeToken<List<BookGroup>>() {}.type
            val remoteGroups: List<BookGroup> = GSON.fromJson(json, type) ?: return
            val localGroupMap = appDb.bookGroupDao.all.associateBy { it.groupId }
            remoteGroups.forEach { remote ->
                if (remote.groupId < 0) return@forEach  // 跳过系统虚拟分组
                val local = localGroupMap[remote.groupId]
                if (local == null) {
                    appDb.bookGroupDao.insert(remote)
                } else {
                    val updated = local.copy(
                        groupName = remote.groupName,
                        cover = remote.cover,
                        bookSort = remote.bookSort,
                        enableRefresh = remote.enableRefresh,
                        show = remote.show,
                        order = remote.order
                    )
                    if (updated != local) {
                        appDb.bookGroupDao.update(updated)
                    }
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            // 云端文件不存在时静默失败
            AppLog.put("下载书籍分组失败\n${it.localizedMessage}", it)
        }
    }

}
