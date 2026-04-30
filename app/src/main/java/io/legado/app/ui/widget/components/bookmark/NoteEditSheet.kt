package io.legado.app.ui.widget.components.bookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReadNote
import io.legado.app.ui.widget.components.AppTextFieldSurface
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.PrimaryButton
import io.legado.app.ui.widget.components.button.SecondaryButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditSheet(
    show: Boolean,
    note: ReadNote,
    onDismiss: () -> Unit,
    onSave: (ReadNote) -> Unit,
    onDelete: (ReadNote) -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedText by remember(note) { mutableStateOf(note.selectedText) }
    var noteContent by remember(note) { mutableStateOf(note.noteContent) }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = note.chapterName,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            AppTextFieldSurface(
                value = selectedText,
                onValueChange = { selectedText = it },
                label = "原文",
                modifier = Modifier.fillMaxWidth(),
                maxLines = 10
            )

            Spacer(modifier = Modifier.height(12.dp))

            AppTextFieldSurface(
                value = noteContent,
                onValueChange = { noteContent = it },
                label = "笔记内容",
                modifier = Modifier.fillMaxWidth(),
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryButton(
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier.weight(1f),
                    text = "删除"
                )

                PrimaryButton(
                    onClick = {
                        onSave(
                            note.copy(
                                selectedText = selectedText,
                                noteContent = noteContent,
                                updatedTime = System.currentTimeMillis(),
                                isSynced = false
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    text = "保存"
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    AppAlertDialog(
        show = showDeleteConfirmDialog,
        onDismissRequest = { showDeleteConfirmDialog = false },
        title = "确认删除",
        text = "你确定要删除这条笔记吗？",
        confirmText = "删除",
        onConfirm = {
            showDeleteConfirmDialog = false
            onDelete(note)
        },
        dismissText = "取消",
        onDismiss = { showDeleteConfirmDialog = false }
    )
}
