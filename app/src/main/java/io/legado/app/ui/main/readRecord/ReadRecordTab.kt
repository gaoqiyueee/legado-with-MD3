package io.legado.app.ui.main.readRecord

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.readRecord.ReadRecordScreen
import io.legado.app.ui.book.readRecord.ReadRecordViewModel
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * 阅读记录主页面 Tab
 * 复用 ReadRecordScreen，但移除返回按钮逻辑
 */
@Composable
fun ReadRecordTab(
    bottomPadding: Dp = 0.dp,
    viewModel: ReadRecordViewModel = koinViewModel(),
    onBookClick: (String, String) -> Unit
) {
    ReadRecordScreen(
        viewModel = viewModel,
        onBackClick = { /* 作为 Tab 时不执行返回操作 */ },
        onBookClick = onBookClick,
        contentWindowInsets = WindowInsets(0),
        extraBottomPadding = bottomPadding
    )
}

/**
 * 处理书籍点击事件
 */
suspend fun handleBookClick(
    context: Context,
    bookName: String,
    bookAuthor: String
) {
    val book = withContext(Dispatchers.IO) {
        appDb.bookDao.getBook(bookName, bookAuthor)
    }
    if (book != null) {
        context.startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    } else {
        context.startActivity<SearchActivity> {
            putExtra("key", bookName)
        }
    }
}