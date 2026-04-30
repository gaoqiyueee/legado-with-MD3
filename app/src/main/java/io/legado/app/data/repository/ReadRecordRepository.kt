package io.legado.app.data.repository

import androidx.room.Transaction
import cn.hutool.core.date.DatePattern
import cn.hutool.core.date.DateUtil
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.help.AppWebDav
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Date
import kotlin.math.max
import kotlin.math.min

class ReadRecordRepository(
    private val dao: ReadRecordDao
) {
    private fun getCurrentDeviceId(): String = ""

    /**
     * 获取总阅读时长流
     */
    fun getTotalReadTime(): Flow<Long> {
        return dao.getTotalReadTime().map { it ?: 0L }
    }

    /**
     * 根据搜索关键字获取最新的阅读书籍列表流
     */
    fun getLatestReadRecords(query: String = ""): Flow<List<ReadRecord>> {
        return if (query.isBlank()) {
            dao.getAllReadRecordsSortedByLastRead()
        } else {
            dao.searchReadRecordsByLastRead(query)
        }
    }

    /**
     * 获取所有的每日统计详情流
     */
    fun getAllRecordDetails(query: String = ""): Flow<List<ReadRecordDetail>> {
        return if (query.isBlank()) {
            dao.getAllDetails()
        } else {
            dao.searchDetails(query)
        }
    }

    fun getAllSessions(): Flow<List<ReadRecordSession>> {
        return dao.getAllSessions(getCurrentDeviceId())
    }

    fun getBookSessions(bookName: String, bookAuthor: String): Flow<List<ReadRecordSession>> {
        return dao.getSessionsByBookFlow(getCurrentDeviceId(), bookName, bookAuthor)
    }

    fun getBookTimelineDays(bookName: String, bookAuthor: String): Flow<List<ReadRecordTimelineDay>> {
        return getBookSessions(bookName, bookAuthor).map { sessions ->
            sessions.groupBy { DateUtil.format(Date(it.startTime), "yyyy-MM-dd") }
                .toSortedMap(compareByDescending { it })
                .map { (date, daySessions) ->
                    ReadRecordTimelineDay(
                        date = date,
                        sessions = daySessions.sortedByDescending { it.startTime }
                    )
                }
        }
    }

    fun getBookReadTime(bookName: String, bookAuthor: String): Flow<Long> {
        return dao.getReadTimeFlow(getCurrentDeviceId(), bookName, bookAuthor).map { it ?: 0L }
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return dao.getReadRecordsByNameExcludingAuthor(
            targetRecord.bookName,
            targetRecord.bookAuthor
        )
    }

    /**
     * 保存一个完整的阅读会话.
     */
    suspend fun saveReadSession(newSession: ReadRecordSession) {
        val segmentDuration = newSession.endTime - newSession.startTime
        dao.insertSession(newSession)
        val dateString = DateUtil.format(Date(newSession.startTime), DatePattern.NORM_DATE_PATTERN)
        updateReadRecordDetail(newSession, segmentDuration, newSession.words, dateString)
        updateReadRecord(newSession, segmentDuration)
        kotlin.runCatching { AppWebDav.uploadReadRecords() }
    }

    private suspend fun updateReadRecord(session: ReadRecordSession, durationDelta: Long) {
        if (durationDelta <= 0) return
        val existingRecord = dao.getReadRecord(session.deviceId, session.bookName, session.bookAuthor)
        if (existingRecord != null) {
            dao.update(
                existingRecord.copy(
                    readTime = existingRecord.readTime + durationDelta,
                    lastRead = session.endTime,
                    // 若现有记录 bookUrl 为空，用本次 session 的 bookUrl 补充
                    bookUrl = existingRecord.bookUrl.ifEmpty { session.bookUrl }
                )
            )
        } else {
            dao.insert(
                ReadRecord(
                    deviceId = session.deviceId,
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    readTime = durationDelta,
                    lastRead = session.endTime,
                    bookUrl = session.bookUrl
                )
            )
        }
    }

    private suspend fun updateReadRecordDetail(
        session: ReadRecordSession,
        durationDelta: Long,
        wordsDelta: Long,
        dateString: String
    ) {
        if (durationDelta <= 0 && wordsDelta <= 0) return
        val existingDetail = dao.getDetail(
            session.deviceId,
            session.bookName,
            session.bookAuthor,
            dateString
        )
        if (existingDetail != null) {
            existingDetail.readTime += durationDelta
            existingDetail.readWords += wordsDelta
            existingDetail.firstReadTime = min(existingDetail.firstReadTime, session.startTime)
            existingDetail.lastReadTime = max(existingDetail.lastReadTime, session.endTime)
            dao.insertDetail(existingDetail)
        } else {
            dao.insertDetail(
                ReadRecordDetail(
                    deviceId = session.deviceId,
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    date = dateString,
                    readTime = durationDelta,
                    readWords = wordsDelta,
                    firstReadTime = session.startTime,
                    lastReadTime = session.endTime
                )
            )
        }
    }

    suspend fun deleteDetail(detail: ReadRecordDetail) {
        dao.deleteDetail(detail)
        dao.deleteSessionsByBookAndDate(
            detail.deviceId,
            detail.bookName,
            detail.bookAuthor,
            detail.date
        )
        updateReadRecordTotal(detail.deviceId, detail.bookName, detail.bookAuthor)
    }

    @Transaction
    suspend fun deleteSession(session: ReadRecordSession) {
        dao.deleteSession(session)

        val dateString = DateUtil.format(Date(session.startTime), "yyyy-MM-dd")
        val remainingSessions =
            dao.getSessionsByBookAndDate(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                dateString
            )

        if (remainingSessions.isEmpty()) {
            val detail = dao.getDetail(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                dateString
            )
            detail?.let { dao.deleteDetail(it) }
        } else {
            val totalTime = remainingSessions.sumOf { it.endTime - it.startTime }
            val totalWords = remainingSessions.sumOf { it.words }
            val firstRead = remainingSessions.minOf { it.startTime }
            val lastRead = remainingSessions.maxOf { it.endTime }

            val existingDetail = dao.getDetail(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                dateString
            )
            existingDetail?.copy(
                readTime = totalTime,
                readWords = totalWords,
                firstReadTime = firstRead,
                lastReadTime = lastRead
            )?.let { dao.insertDetail(it) }
        }

        updateReadRecordTotal(session.deviceId, session.bookName, session.bookAuthor)
    }

    private suspend fun updateReadRecordTotal(deviceId: String, bookName: String, bookAuthor: String) {
        val allRemainingSessions = dao.getSessionsByBook(deviceId, bookName, bookAuthor)

        if (allRemainingSessions.isEmpty()) {
            dao.getReadRecord(deviceId, bookName, bookAuthor)?.let { dao.deleteReadRecord(it) }
        } else {
            val totalTime = allRemainingSessions.sumOf { it.endTime - it.startTime }
            val lastRead = allRemainingSessions.maxOf { it.endTime }

            dao.getReadRecord(deviceId, bookName, bookAuthor)?.copy(
                readTime = totalTime,
                lastRead = lastRead
            )?.let { dao.update(it) }
        }
    }

    suspend fun deleteReadRecord(record: ReadRecord) {
        dao.deleteReadRecord(record)
        dao.deleteDetailsByBook(record.deviceId, record.bookName, record.bookAuthor)
        dao.deleteSessionsByBook(record.deviceId, record.bookName, record.bookAuthor)
    }

    /**
     * 以 bookUrl 为稳定标识，自动合并同一本书被拆分成多条的阅读记录。
     * 通常在阅读记录页面打开时调用一次。
     * 流程：
     * 1. 回填空 bookUrl（根据 books 表匹配 name+author）
     * 2. 查找同一 bookUrl 下存在多条 ReadRecord 的情况
     * 3. 以当前书架中的 name/author 为目标，将其余记录合并进去
     */
    @Transaction
    suspend fun autoMergeByBookUrl() {
        // 1. 回填
        dao.backfillBookUrls()
        dao.backfillSessionBookUrls()

        // 2. 找出所有有 bookUrl 的记录，按 bookUrl 分组，合并重复
        val allRecords = dao.all
        val withUrl = allRecords.filter { it.bookUrl.isNotEmpty() }
        val groups = withUrl.groupBy { it.bookUrl }

        groups.forEach { (bookUrl, records) ->
            if (records.size <= 1) return@forEach
            val target = records.maxByOrNull { it.lastRead } ?: return@forEach
            val sources = records.filter {
                it.bookName != target.bookName || it.bookAuthor != target.bookAuthor
            }
            if (sources.isEmpty()) return@forEach
            sources.forEach { source ->
                renameAndMergeReadRecord(source.bookName, source.bookAuthor, target.bookName, target.bookAuthor)
            }
            // 合并后再回填 bookUrl
            dao.getReadRecord(target.deviceId, target.bookName, target.bookAuthor)?.let { merged ->
                if (merged.bookUrl.isEmpty()) {
                    dao.insert(merged.copy(bookUrl = bookUrl))
                }
            }
        }

        // 3. 回退策略：对 bookUrl 为空的记录，按书名分组合并
        //    适用于修改作者前创建的历史记录（迁移时无法回填 bookUrl）
        val withoutUrl = dao.all.filter { it.bookUrl.isEmpty() }
        val byName = withoutUrl.groupBy { it.bookName }
        byName.forEach { (_, nameRecords) ->
            if (nameRecords.size <= 1) return@forEach
            val target = nameRecords.maxByOrNull { it.lastRead } ?: return@forEach
            val sources = nameRecords.filter { it.bookAuthor != target.bookAuthor }
            if (sources.isEmpty()) return@forEach
            sources.forEach { source ->
                renameAndMergeReadRecord(source.bookName, source.bookAuthor, target.bookName, target.bookAuthor)
            }
        }
    }

    /**
     * 将阅读记录从旧书名/作者完整迁移到新书名/作者，包括 readRecord、readRecordDetail、readRecordSession。
     * 若新书名/作者下已有记录，则合并（累加时长、合并每日详情、更新会话归属）。
     */
    @Transaction
    suspend fun renameAndMergeReadRecord(
        oldName: String, oldAuthor: String,
        newName: String, newAuthor: String
    ) {
        val deviceId = getCurrentDeviceId()
        val oldRecord = dao.getReadRecord(deviceId, oldName, oldAuthor) ?: return

        val existingNew = dao.getReadRecord(deviceId, newName, newAuthor)
        // 保留 bookUrl：优先用已有新记录的，其次用旧记录的
        val mergedBookUrl = existingNew?.bookUrl?.takeIf { it.isNotEmpty() }
            ?: oldRecord.bookUrl

        // Merge top-level ReadRecord
        if (existingNew == null) {
            dao.insert(oldRecord.copy(bookName = newName, bookAuthor = newAuthor, bookUrl = mergedBookUrl))
        } else {
            dao.insert(
                existingNew.copy(
                    readTime = existingNew.readTime + oldRecord.readTime,
                    lastRead = max(existingNew.lastRead, oldRecord.lastRead),
                    bookUrl = mergedBookUrl
                )
            )
        }
        dao.deleteReadRecord(oldRecord)

        // Merge readRecordDetail
        val oldDetails = dao.getDetailsByBook(deviceId, oldName, oldAuthor)
        oldDetails.forEach { detail ->
            val existingDetail = dao.getDetail(deviceId, newName, newAuthor, detail.date)
            if (existingDetail == null) {
                dao.insertDetail(detail.copy(bookName = newName, bookAuthor = newAuthor))
            } else {
                dao.insertDetail(
                    existingDetail.copy(
                        readTime = existingDetail.readTime + detail.readTime,
                        readWords = existingDetail.readWords + detail.readWords,
                        firstReadTime = min(existingDetail.firstReadTime, detail.firstReadTime),
                        lastReadTime = max(existingDetail.lastReadTime, detail.lastReadTime)
                    )
                )
            }
        }
        dao.deleteDetailsByBook(deviceId, oldName, oldAuthor)

        // Migrate readRecordSession
        val oldSessions = dao.getSessionsByBook(deviceId, oldName, oldAuthor)
        oldSessions.forEach { session ->
            dao.updateSession(session.copy(bookName = newName, bookAuthor = newAuthor))
        }
    }

    @Transaction
    suspend fun mergeReadRecordInto(targetRecord: ReadRecord, sourceRecords: List<ReadRecord>) {
        sourceRecords.forEach { sourceRecord ->
            mergeSingleReadRecordInto(targetRecord, sourceRecord)
        }
    }

    @Transaction
    private suspend fun mergeSingleReadRecordInto(targetRecord: ReadRecord, sourceRecord: ReadRecord) {
        if (targetRecord == sourceRecord) return
        if (targetRecord.bookName != sourceRecord.bookName) return

        val source = dao.getReadRecord(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor
        ) ?: return

        val target = dao.getReadRecord(
            targetRecord.deviceId,
            targetRecord.bookName,
            targetRecord.bookAuthor
        ) ?: targetRecord

        dao.insert(
            target.copy(
                readTime = target.readTime + source.readTime,
                lastRead = max(target.lastRead, source.lastRead)
            )
        )

        val sourceDetails = dao.getDetailsByBook(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor
        )
        sourceDetails.forEach { detail ->
            val existingTargetDetail = dao.getDetail(
                targetRecord.deviceId,
                targetRecord.bookName,
                targetRecord.bookAuthor,
                detail.date
            )
            if (existingTargetDetail == null) {
                dao.insertDetail(
                    detail.copy(
                        bookAuthor = targetRecord.bookAuthor
                    )
                )
            } else {
                dao.insertDetail(
                    existingTargetDetail.copy(
                        readTime = existingTargetDetail.readTime + detail.readTime,
                        readWords = existingTargetDetail.readWords + detail.readWords,
                        firstReadTime = min(existingTargetDetail.firstReadTime, detail.firstReadTime),
                        lastReadTime = max(existingTargetDetail.lastReadTime, detail.lastReadTime)
                    )
                )
            }
        }
        dao.deleteDetailsByBook(sourceRecord.deviceId, sourceRecord.bookName, sourceRecord.bookAuthor)

        val sourceSessions = dao.getSessionsByBook(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor
        )
        sourceSessions.forEach { session ->
            dao.updateSession(session.copy(bookAuthor = targetRecord.bookAuthor))
        }

        dao.deleteReadRecord(source)
        updateReadRecordTotal(targetRecord.deviceId, targetRecord.bookName, targetRecord.bookAuthor)
    }

}
