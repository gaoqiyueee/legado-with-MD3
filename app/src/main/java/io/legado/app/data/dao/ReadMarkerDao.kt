package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadMarker
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadMarkerDao {

    @get:Query(
        "SELECT * FROM read_markers ORDER BY bookName, bookAuthor, chapterIndex, chapterPos"
    )
    val all: List<ReadMarker>

    @Query(
        """SELECT * FROM read_markers
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor
        ORDER BY chapterIndex, chapterPos"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<ReadMarker>>

    @Query(
        """SELECT * FROM read_markers
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor
        ORDER BY chapterIndex, chapterPos"""
    )
    fun getByBook(bookName: String, bookAuthor: String): List<ReadMarker>

    @Query(
        """SELECT * FROM read_markers
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor
        AND chapterIndex = :chapterIndex AND chapterPos = :chapterPos
        LIMIT 1"""
    )
    fun getByPosition(
        bookName: String,
        bookAuthor: String,
        chapterIndex: Int,
        chapterPos: Int
    ): ReadMarker?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg marker: ReadMarker)

    @Delete
    fun delete(vararg marker: ReadMarker)

    @Query("DELETE FROM read_markers WHERE bookName = :bookName AND bookAuthor = :bookAuthor")
    fun deleteByBook(bookName: String, bookAuthor: String)

}
