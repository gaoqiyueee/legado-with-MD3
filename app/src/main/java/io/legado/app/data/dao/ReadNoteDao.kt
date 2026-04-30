package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.ReadNote
import kotlinx.coroutines.flow.Flow

/**
 * 阅读笔记数据访问对象
 */
@Dao
interface ReadNoteDao {

    /**
     * 获取所有笔记
     */
    @get:Query("SELECT * FROM readNotes WHERE deleted = 0 ORDER BY updatedTime DESC")
    val all: List<ReadNote>

    /**
     * 获取所有笔记（Flow）
     */
    @Query("SELECT * FROM readNotes WHERE deleted = 0 ORDER BY updatedTime DESC")
    fun flowAll(): Flow<List<ReadNote>>

    /**
     * 获取某本书的所有笔记
     */
    @Query(
        """SELECT * FROM readNotes
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor AND deleted = 0
        ORDER BY chapterIndex, chapterPos"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<ReadNote>>

    /**
     * 获取某本书的所有笔记（同步用）
     */
    @Query(
        """SELECT * FROM readNotes
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor AND deleted = 0
        ORDER BY chapterIndex, chapterPos"""
    )
    fun getByBook(bookName: String, bookAuthor: String): List<ReadNote>

    /**
     * 获取指定章节的笔记
     */
    @Query(
        """SELECT * FROM readNotes
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor
        AND chapterIndex = :chapterIndex AND deleted = 0
        ORDER BY chapterPos"""
    )
    fun getByChapter(bookName: String, bookAuthor: String, chapterIndex: Int): List<ReadNote>

    /**
     * 搜索笔记
     */
    @Query(
        """SELECT * FROM readNotes
        WHERE deleted = 0
        AND (bookName LIKE '%'||:query||'%'
        OR selectedText LIKE '%'||:query||'%'
        OR noteContent LIKE '%'||:query||'%'
        OR chapterName LIKE '%'||:query||'%')
        ORDER BY updatedTime DESC"""
    )
    fun search(query: String): Flow<List<ReadNote>>

    /**
     * 搜索某本书的笔记
     */
    @Query(
        """SELECT * FROM readNotes
        WHERE bookName = :bookName AND bookAuthor = :bookAuthor AND deleted = 0
        AND (selectedText LIKE '%'||:query||'%' OR noteContent LIKE '%'||:query||'%')
        ORDER BY updatedTime DESC"""
    )
    fun searchByBook(bookName: String, bookAuthor: String, query: String): Flow<List<ReadNote>>

    /**
     * 获取未同步的笔记
     */
    @Query("SELECT * FROM readNotes WHERE isSynced = 0 AND deleted = 0")
    fun getUnsynced(): List<ReadNote>

    /**
     * 获取已删除但未同步的笔记
     */
    @Query("SELECT * FROM readNotes WHERE deleted = 1")
    fun getDeleted(): List<ReadNote>

    /**
     * 获取指定时间范围内的笔记
     */
    @Query(
        """SELECT * FROM readNotes
        WHERE deleted = 0
        AND updatedTime >= :startTime AND updatedTime <= :endTime
        ORDER BY updatedTime DESC"""
    )
    fun getByTimeRange(startTime: Long, endTime: Long): List<ReadNote>

    /**
     * 插入笔记
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: ReadNote)

    /**
     * 批量插入笔记
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg notes: ReadNote)

    /**
     * 批量插入笔记列表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notes: List<ReadNote>)

    /**
     * 更新笔记
     */
    @Update
    suspend fun update(note: ReadNote)

    /**
     * 删除笔记（软删除）
     */
    @Query("UPDATE readNotes SET deleted = 1, updatedTime = :updatedTime WHERE noteId = :noteId")
    suspend fun delete(noteId: String, updatedTime: Long = System.currentTimeMillis())

    /**
     * 永久删除笔记
     */
    @Delete
    suspend fun deletePermanently(note: ReadNote)

    /**
     * 永久删除笔记列表
     */
    @Delete
    suspend fun deletePermanentlyAll(notes: List<ReadNote>)

    /**
     * 永久删除已标记为删除的笔记
     */
    @Query("DELETE FROM readNotes WHERE deleted = 1")
    suspend fun purgeDeleted()

    /**
     * 更新同步状态
     */
    @Query("UPDATE readNotes SET isSynced = 1 WHERE noteId = :noteId")
    suspend fun markAsSynced(noteId: String)

    /**
     * 批量更新同步状态
     */
    @Query("UPDATE readNotes SET isSynced = 1 WHERE noteId IN (:noteIds)")
    suspend fun markAsSyncedAll(noteIds: List<String>)

    /**
     * 统计笔记数量
     */
    @Query("SELECT COUNT(*) FROM readNotes WHERE deleted = 0")
    suspend fun count(): Int

    /**
     * 统计某本书的笔记数量
     */
    @Query("SELECT COUNT(*) FROM readNotes WHERE bookName = :bookName AND bookAuthor = :bookAuthor AND deleted = 0")
    suspend fun countByBook(bookName: String, bookAuthor: String): Int
}