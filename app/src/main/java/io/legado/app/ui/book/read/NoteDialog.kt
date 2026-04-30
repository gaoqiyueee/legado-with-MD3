package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadNote
import io.legado.app.databinding.DialogBookmarkBinding
import io.legado.app.help.AppWebDav
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.gone
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加/编辑笔记 Dialog，复用 dialog_bookmark 布局
 */
class NoteDialog() : BaseBottomSheetDialogFragment(R.layout.dialog_bookmark) {

    constructor(note: ReadNote) : this() {
        arguments = Bundle().apply {
            putString("noteId", note.noteId)
            putString("bookName", note.bookName)
            putString("bookAuthor", note.bookAuthor)
            putInt("chapterIndex", note.chapterIndex)
            putInt("chapterPos", note.chapterPos)
            putString("chapterName", note.chapterName)
            putString("selectedText", note.selectedText)
            putString("noteContent", note.noteContent)
        }
    }

    private val binding by viewBinding(DialogBookmarkBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val args = arguments ?: run { dismiss(); return }
        val noteId = args.getString("noteId") ?: run { dismiss(); return }

        val note = ReadNote(
            noteId = noteId,
            bookName = args.getString("bookName", ""),
            bookAuthor = args.getString("bookAuthor", ""),
            chapterIndex = args.getInt("chapterIndex", 0),
            chapterPos = args.getInt("chapterPos", 0),
            chapterName = args.getString("chapterName", ""),
            selectedText = args.getString("selectedText", ""),
            noteContent = args.getString("noteContent", "")
        )

        binding.run {
            tvChapterName.text = note.chapterName
            editBookText.setText(note.selectedText)
            editContent.hint = "笔记内容"
            editContent.setText(note.noteContent)
            // 笔记新建模式不显示删除按钮
            btnDelete.gone()

            btnCancel.setOnClickListener { dismiss() }

            btnOk.setOnClickListener {
                val updatedNote = note.copy(
                    selectedText = editBookText.text?.toString() ?: "",
                    noteContent = editContent.text?.toString() ?: "",
                    updatedTime = System.currentTimeMillis(),
                    isSynced = false
                )
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readNoteDao.insert(updatedNote)
                        kotlin.runCatching { AppWebDav.uploadNotes() }
                    }
                    dismiss()
                }
            }
        }
    }
}
