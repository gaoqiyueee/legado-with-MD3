package io.legado.app.ui.book.note

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.dao.ReadNoteDao
import io.legado.app.data.entities.ReadNote
import io.legado.app.utils.toastOnUi
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

data class NoteGroupHeader(
    val bookName: String,
    val bookAuthor: String
) {
    override fun toString(): String = "$bookName|$bookAuthor"
}

@Immutable
data class NoteItemUi(
    val id: String,
    val selectedText: String,
    val noteContent: String,
    val chapterName: String,
    val bookName: String,
    val bookAuthor: String,
    val rawNote: ReadNote
)

@Immutable
data class NoteUiState(
    val isLoading: Boolean = false,
    val notes: Map<NoteGroupHeader, List<NoteItemUi>> = emptyMap(),
    val error: Throwable? = null,
    val searchQuery: String = "",
    val collapsedGroups: Set<String> = emptySet()
)

class AllNoteViewModel(
    application: Application,
    private val readNoteDao: ReadNoteDao
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<NoteUiState> = combine(
        _searchQuery,
        _collapsedGroups,
        readNoteDao.flowAll()
    ) { query, collapsed, allNotes ->

        val filteredList = if (query.isBlank()) {
            allNotes
        } else {
            allNotes.filter {
                it.bookName.contains(query, ignoreCase = true) ||
                        it.noteContent.contains(query, ignoreCase = true) ||
                        it.selectedText.contains(query, ignoreCase = true) ||
                        it.bookAuthor.contains(query, ignoreCase = true)
            }
        }

        val grouped = filteredList.asSequence()
            .map { note ->
                NoteItemUi(
                    id = note.noteId,
                    selectedText = note.selectedText,
                    noteContent = note.noteContent,
                    chapterName = note.chapterName,
                    bookName = note.bookName,
                    bookAuthor = note.bookAuthor,
                    rawNote = note
                )
            }
            .groupBy { item ->
                NoteGroupHeader(item.bookName, item.bookAuthor)
            }

        NoteUiState(
            isLoading = false,
            notes = grouped,
            searchQuery = query,
            collapsedGroups = collapsed
        )
    }.catch { e ->
        emit(NoteUiState(isLoading = false, error = e))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NoteUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun toggleGroupCollapse(groupKey: NoteGroupHeader) {
        val stringKey = groupKey.toString()
        _collapsedGroups.update { current ->
            if (current.contains(stringKey)) current - stringKey else current + stringKey
        }
    }

    fun toggleAllCollapse(currentKeys: Set<NoteGroupHeader>) {
        val stringKeys = currentKeys.map { it.toString() }.toSet()
        _collapsedGroups.update { current ->
            if (current.containsAll(stringKeys) && stringKeys.isNotEmpty()) {
                emptySet()
            } else {
                stringKeys
            }
        }
    }

    fun updateNote(note: ReadNote) {
        viewModelScope.launch(Dispatchers.IO) {
            readNoteDao.update(note.copy(updatedTime = System.currentTimeMillis(), isSynced = false))
        }
    }

    fun deleteNote(note: ReadNote) {
        viewModelScope.launch(Dispatchers.IO) {
            readNoteDao.delete(note.noteId)
        }
    }
}
