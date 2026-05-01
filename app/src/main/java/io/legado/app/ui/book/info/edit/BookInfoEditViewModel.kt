package io.legado.app.ui.book.info.edit

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.inputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream

data class BookInfoEditUiState(
    val name: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String? = null,
    val remark: String? = null,
    val selectedType: String = "文本",
    val bookTypes: List<String> = listOf("文本", "音频", "图片"),
    val fixedType: Boolean = false,
    val book: Book? = null,
)

class BookInfoEditViewModel(application: Application) : BaseViewModel(application), KoinComponent {
    var book: Book? = null
    private val _uiState = MutableStateFlow(BookInfoEditUiState())
    val uiState: StateFlow<BookInfoEditUiState> = _uiState.asStateFlow()
    private val readRecordRepository: ReadRecordRepository by inject()

    fun loadBook(bookUrl: String) {
        execute {
            book = appDb.bookDao.getBook(bookUrl)
            book?.let {
                val selectedTypeIndex = when {
                    it.isImage -> 2
                    it.isAudio -> 1
                    else -> 0
                }
                _uiState.value = BookInfoEditUiState(
                    name = it.name,
                    author = it.author,
                    coverUrl = it.getDisplayCover(),
                    intro = it.getDisplayIntro(),
                    remark = it.remark,
                    selectedType = _uiState.value.bookTypes[selectedTypeIndex],
                    fixedType = it.config.fixedType,
                    book = it
                )
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun onAuthorChange(author: String) {
        _uiState.value = _uiState.value.copy(author = author)
    }

    fun onCoverUrlChange(coverUrl: String) {
        _uiState.value = _uiState.value.copy(coverUrl = coverUrl)
    }

    fun onIntroChange(intro: String) {
        _uiState.value = _uiState.value.copy(intro = intro)
    }

    fun onRemarkChange(remark: String) {
        _uiState.value = _uiState.value.copy(remark = remark)
    }

    fun onBookTypeChange(bookType: String) {
        _uiState.value = _uiState.value.copy(selectedType = bookType)
    }

    fun onFixedTypeChange(fixed: Boolean) {
        _uiState.value = _uiState.value.copy(fixedType = fixed)
    }

    fun resetCover() {
        _uiState.value = _uiState.value.copy(coverUrl = book?.coverUrl ?: "")
    }

    fun save(onSuccess: () -> Unit) {
        execute {
            val currentState = _uiState.value
            book?.let { book ->
                val oldBook = book.copy()
                book.name = currentState.name
                book.author = currentState.author
                book.remark = currentState.remark
                val local = if (book.isLocal) BookType.local else 0
                val bookType = when (currentState.selectedType) {
                    currentState.bookTypes[2] -> BookType.image or local
                    currentState.bookTypes[1] -> BookType.audio or local
                    else -> BookType.text or local
                }
                book.removeType(BookType.local, BookType.image, BookType.audio, BookType.text)
                book.addType(bookType)
                book.config.fixedType = currentState.fixedType
                book.customCoverUrl = if (currentState.coverUrl == book.coverUrl) null else currentState.coverUrl
                book.customIntro = if (currentState.intro == book.intro) null else currentState.intro
                BookHelp.updateCacheFolder(oldBook, book)

                if (ReadBook.book?.bookUrl == book.bookUrl) {
                    ReadBook.book = book
                }
                appDb.bookDao.update(book)

                // 保存后立即将用户自定义信息上传到云端
                kotlin.runCatching { AppWebDav.uploadBookInfo(book) }

                // 如果书名或作者变更，级联更新关联表
                val nameChanged = oldBook.name != book.name
                val authorChanged = oldBook.author != book.author
                if (nameChanged || authorChanged) {
                    cascadeUpdateBookInfo(oldBook.name, oldBook.author, book.name, book.author)
                }
            }
        }.onSuccess {
            onSuccess.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }

    /**
     * 将书名/作者变更级联同步到书签、阅读记录等关联表。
     * 以 bookUrl 为真实书籍标识，name/author 只是展示字段。
     */
    private suspend fun cascadeUpdateBookInfo(
        oldName: String, oldAuthor: String,
        newName: String, newAuthor: String
    ) {
        // 1. 更新书签
        val bookmarks = appDb.bookmarkDao.getByBook(oldName, oldAuthor)
        bookmarks.forEach { bm ->
            appDb.bookmarkDao.update(bm.copy(bookName = newName, bookAuthor = newAuthor))
        }

        // 2. 更新阅读记录（readRecord、readRecordDetail、readRecordSession 全部迁移）
        readRecordRepository.renameAndMergeReadRecord(oldName, oldAuthor, newName, newAuthor)
    }

    fun coverChangeTo(context: Context, uri: Uri) {
        execute {
            runCatching {
                context.externalCacheDir?.let { externalCacheDir ->
                    val file = File(externalCacheDir, "covers")
                    val suffix = context.contentResolver.getType(uri)?.substringAfterLast("/") ?: "jpg"
                    val fileName = uri.inputStream(context).getOrThrow().use { MD5Utils.md5Encode(it) } + ".$suffix"
                    val coverFile = FileUtils.createFileIfNotExist(file, fileName)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        FileOutputStream(coverFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    _uiState.value = _uiState.value.copy(coverUrl = coverFile.absolutePath)
                } ?: run {
                    AppLog.put("External cache directory is null", Throwable("Null directory"), true)
                }
            }.onFailure {
                AppLog.put("书籍封面保存失败\n$it", it, true)
            }
        }
    }
}
