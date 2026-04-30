package io.legado.app.data.entities.readRecord

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "readRecord", primaryKeys = ["deviceId", "bookName", "bookAuthor"])
data class ReadRecord(
    var deviceId: String = "",
    var bookName: String = "",
    @ColumnInfo(defaultValue = "")
    var bookAuthor: String = "",
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis(),
    /** 书籍的稳定唯一标识，对应 Book.bookUrl，不随书名/作者修改而变化 */
    @ColumnInfo(defaultValue = "")
    var bookUrl: String = ""
)