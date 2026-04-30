package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 阅读笔记实体
 */
@Entity(
    tableName = "readNotes",
    indices = [Index(value = ["bookName", "bookAuthor"])]
)
data class ReadNote(
    @PrimaryKey
    val noteId: String = UUID.randomUUID().toString(),

    // 书籍信息
    @ColumnInfo(index = true)
    val bookName: String = "",

    @ColumnInfo(index = true)
    val bookAuthor: String = "",

    // 位置信息
    val chapterIndex: Int = 0,
    val chapterPos: Int = 0,
    val chapterName: String = "",

    // 笔记内容
    val selectedText: String = "",    // 选中的文本
    val noteContent: String = "",     // 笔记内容

    // 时间信息
    val createdTime: Long = System.currentTimeMillis(),
    val updatedTime: Long = System.currentTimeMillis(),

    // 其他信息
    @ColumnInfo(defaultValue = "0")
    val isSynced: Boolean = false,     // 是否已同步到 WebDAV

    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false      // 是否已删除（用于 WebDAV 同步）
)