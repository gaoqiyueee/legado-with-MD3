package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "read_markers",
    indices = [(Index(value = ["bookName", "bookAuthor"], unique = false))]
)
data class ReadMarker(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    val bookName: String = "",
    val bookAuthor: String = "",
    val chapterIndex: Int = 0,
    val chapterPos: Int = 0,
    val chapterName: String = "",
    val displayText: String = ""
) : Parcelable
