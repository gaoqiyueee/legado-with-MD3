package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadNote
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.exception.NoStackTraceException
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
     * 直接将本地书签全量覆盖云端，不再做云端合并（合并逻辑移至 downloadBookmarks）
     */
    suspend fun uploadBookmarks() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val localBookKeys = appDb.bookDao.all
                .map { it.name to it.author }.toSet()
            val bookmarks = appDb.bookmarkDao.all
                .filter { (it.bookName to it.bookAuthor) in localBookKeys }
            if (bookmarks.isEmpty()) return
            val json = GSON.toJson(bookmarks)
            WebDav(bookmarksWebDavUrl, authorization).makeAsDir()
            WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization).upload(
                json.toByteArray(),
                "application/json"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传书签失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV 下载书签并同步到本地（替换而非合并）
     * 对云端有数据的书籍，先删除本地书签再插入云端书签，确保删除操作可以跨设备传播。
     * 对云端无数据的书籍（本设备独有），保持本地书签不变。
     */
    suspend fun downloadBookmarks() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val byteArray = WebDav("${bookmarksWebDavUrl}bookmarks.json", authorization).download()
            val json = String(byteArray)
            if (!json.isJson()) return
            val type = object : TypeToken<List<Bookmark>>() {}.type
            val remoteBookmarks: List<Bookmark> = GSON.fromJson(json, type) ?: return
            val localBookKeys = appDb.bookDao.all
                .map { it.name to it.author }.toSet()
            val toSync = remoteBookmarks
                .filter { (it.bookName to it.bookAuthor) in localBookKeys }
            // 对云端有数据的书籍执行替换：先删除本地旧书签，再插入云端书签
            // 这样其他设备上的删除操作才能传播到本设备
            val booksInCloud = toSync.map { it.bookName to it.bookAuthor }.toSet()
            booksInCloud.forEach { (name, author) ->
                appDb.bookmarkDao.deleteByBook(name, author)
            }
            if (toSync.isNotEmpty()) {
                appDb.bookmarkDao.insert(*toSync.toTypedArray())
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("下载书签失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 上传阅读时长记录到 WebDAV
     * 上传前先合并云端记录（取 readTime 最大值），再全量上传，避免覆盖其他设备的时长
     */
    suspend fun uploadReadRecords() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        kotlin.runCatching {
            val localBookKeys = appDb.bookDao.all
                .map { it.name to it.author }.toSet()
            // 先把云端记录合并到本地（取较大值）
            kotlin.runCatching {
                val byteArray = WebDav("${readRecordsWebDavUrl}records.json", authorization).download()
                val json = String(byteArray)
                if (json.isJson()) {
                    val type = object : TypeToken<List<ReadRecord>>() {}.type
                    val remoteRecords: List<ReadRecord> = GSON.fromJson(json, type) ?: emptyList()
                    val localRecords = appDb.readRecordDao.all
                        .associateBy { Triple(it.deviceId, it.bookName, it.bookAuthor) }
                    remoteRecords.forEach { remote ->
                        if ((remote.bookName to remote.bookAuthor) !in localBookKeys) return@forEach
                        val key = Triple(remote.deviceId, remote.bookName, remote.bookAuthor)
                        val local = localRecords[key]
                        if (local == null) {
                            appDb.readRecordDao.insert(remote)
                        } else if (remote.readTime > local.readTime) {
                            appDb.readRecordDao.insert(
                                local.copy(
                                    readTime = remote.readTime,
                                    lastRead = maxOf(local.lastRead, remote.lastRead)
                                )
                            )
                        }
                    }
                }
            }
            // 上传合并后的本地记录
            val records = appDb.readRecordDao.all
                .filter { (it.bookName to it.bookAuthor) in localBookKeys }
            if (records.isEmpty()) return
            val json = GSON.toJson(records)
            WebDav(readRecordsWebDavUrl, authorization).makeAsDir()
            WebDav("${readRecordsWebDavUrl}records.json", authorization).upload(
                json.toByteArray(),
                "application/json"
            )
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传阅读记录失败\n${it.localizedMessage}", it)
        }
    }

    /**
     * 从 WebDAV 下载阅读时长记录并合并到本地
     * 若云端 readTime 大于本地，将差值累加到本地（避免重复计算已同步的时长）
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
            val localBookKeys = appDb.bookDao.all
                .map { it.name to it.author }.toSet()
            val localRecords = appDb.readRecordDao.all
                .associateBy { Triple(it.deviceId, it.bookName, it.bookAuthor) }
                .toMutableMap()
            remoteRecords.forEach { remote ->
                if ((remote.bookName to remote.bookAuthor) !in localBookKeys) return@forEach
                val key = Triple(remote.deviceId, remote.bookName, remote.bookAuthor)
                val local = localRecords[key]
                if (local == null) {
                    appDb.readRecordDao.insert(remote)
                } else if (remote.readTime > local.readTime) {
                    // 云端比本地多出来的时间是"其他设备新增的"，累加而非覆盖
                    val delta = remote.readTime - local.readTime
                    appDb.readRecordDao.insert(
                        local.copy(
                            readTime = local.readTime + delta,
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

}
