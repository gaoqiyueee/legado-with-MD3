package io.legado.app.ui.book.note

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

/**
 * 所有笔记
 */
class AllNoteActivity : BaseComposeActivity() {
    @Composable
    override fun Content() {
        MaterialTheme {
            AllNoteScreen(
                onBack = { finish() }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
