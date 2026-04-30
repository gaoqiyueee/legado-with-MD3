package io.legado.app.ui.book.annotation

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.book.bookmark.AllBookmarkScreen

/**
 * 书签页面
 */
class AllAnnotationActivity : BaseComposeActivity() {

    @Composable
    override fun Content() {
        MaterialTheme {
            AllBookmarkScreen(onBack = { finish() })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
