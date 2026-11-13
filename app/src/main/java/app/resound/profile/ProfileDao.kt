package app.resound.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.flow.Flow

/**
 * 长期记忆的唯一写入口。LLM 只给候选，所有写入都经这里的事务方法，
 * 保证“事实 + 出处 + 审计”要么一起成功、要么都不写。
 */
@Dao
abstract class ProfileDao {
    // ---------- 读 ----------
    @Query("SELECT * FROM profile_facts ORDER BY updatedAt DESC")
    abstract fun observeFacts(): Flow<List<ProfileFactEntity>>

    @Query("SELECT * FROM profile_facts ORDER BY updatedAt DESC")
    abstract suspend fun facts(): List<ProfileFactEntity>

    @Query("SELECT * FROM profile_facts WHERE state = :state ORDER BY updatedAt DESC")
    abstract suspend fun factsInState(state: String): List<ProfileFactEntity>

    @Query("SELECT * FROM profile_facts WHERE id = :id")
    abstract suspend fun fact(id: Long): ProfileFactEntity?

    @Query("SELECT * FROM profile_facts WHERE subject = :subject AND scope = :scope AND key = :key AND state = :state LIMIT 1")
    abstract suspend fun factBySlot(subject: String, scope: String, key: String, state: String): ProfileFactEntity?

    @Query("SELECT * FROM profile_evidence WHERE factId = :factId ORDER BY addedAt")
    abstract suspend fun evidence(factId: Long): List<ProfileEvidenceEntity>

    @Query("SELECT * FROM profile_evidence WHERE factId IN (:ids)")
    abstract suspend fun evidenceFor(ids: List<Long>): List<ProfileEvidenceEntity>

    @Query("SELECT * FROM profile_change_log WHERE factId = :factId ORDER BY at DESC")
    abstract suspend fun changes(factId: Long): List<ProfileChangeEntity>

    // ---------- 事实/出处/审计写入 ----------
    @Insert
    abstract suspend fun insertFact(row: ProfileFactEntity): Long

    @Update
    abstract suspend fun updateFact(row: ProfileFactEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEvidence(rows: List<ProfileEvidenceEntity>)

    @Insert
    abstract suspend fun insertChange(row: ProfileChangeEntity)

    // ---------- 同步状态/防复活 ----------
    @Query("SELECT * FROM profile_sync_state WHERE id = 1")
    abstract suspend fun sync(): ProfileSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveSync(row: ProfileSyncStateEntity)

    @Query("SELECT * FROM profile_suppression")
    abstract suspend fun suppressions(): List<ProfileSuppressionEntity>

    @Query("SELECT * FROM profile_suppression WHERE subject = :subject AND scope = :scope AND key = :key LIMIT 1")
    abstract suspend fun suppression(subject: String, scope: String, key: String): ProfileSuppressionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addSuppression(row: ProfileSuppressionEntity)

    @Query("DELETE FROM profile_suppression")
    abstract suspend fun clearSuppressions()

    // ---------- 事务：建事实 ----------
    /** 新建事实并追加出处与审计；返回新事实 id。 */
    @Transaction
    open suspend fun createFact(
        fact: ProfileFactEntity,
        evidence: List<ProfileEvidenceEntity>,
        change: ProfileChangeEntity,
    ): Long {
        val id = insertFact(fact)
        if (evidence.isNotEmpty()) insertEvidence(evidence.map { it.copy(factId = id) })
        insertChange(change.copy(factId = id))
        return id
    }

    /** 用户/模型确认现有事实：追加出处，版本号 +1，记确认时间。 */
    @Transaction
    open suspend fun confirmFact(
        factId: Long,
        evidence: List<ProfileEvidenceEntity>,
        at: Long,
        actor: String = ProfileOrigin.AUTO,
    ) {
        val row = fact(factId) ?: return
        if (evidence.isNotEmpty()) insertEvidence(evidence.map { it.copy(factId = factId) })
        updateFact(row.copy(lastConfirmedAt = at, updatedAt = at, revision = row.revision + 1))
        insertChange(
            ProfileChangeEntity(
                factId = factId, operation = "confirm", newValue = row.value, at = at, actor = actor,
            ),
        )
    }

    /** 明确变化：旧事实置 superseded 并记有效区间终点，新事实 active；两条审计都能追溯。 */
    @Transaction
    open suspend fun replaceFact(
        oldId: Long,
        newFact: ProfileFactEntity,
        evidence: List<ProfileEvidenceEntity>,
        at: Long,
        actor: String = ProfileOrigin.AUTO,
        reason: String? = null,
    ) {
        val old = fact(oldId) ?: return
        updateFact(
            old.copy(
                state = ProfileState.SUPERSEDED, validTo = at, updatedAt = at, revision = old.revision + 1,
            ),
        )
        val newId = insertFact(newFact)
        if (evidence.isNotEmpty()) insertEvidence(evidence.map { it.copy(factId = newId) })
        insertChange(
            ProfileChangeEntity(
                factId = oldId, operation = "supersede", oldValue = old.value, newValue = newFact.value,
                reason = reason, at = at, actor = actor,
            ),
        )
        insertChange(
            ProfileChangeEntity(
                factId = newId, operation = "update", oldValue = old.value, newValue = newFact.value,
                reason = reason, at = at, actor = actor,
            ),
        )
    }

    /** 改状态（禁用/启用/转待确认等），同时记审计。 */
    @Transaction
    open suspend fun setState(
        factId: Long,
        state: String,
        at: Long,
        operation: String,
        actor: String,
        reason: String? = null,
    ) {
        val row = fact(factId) ?: return
        updateFact(row.copy(state = state, updatedAt = at, revision = row.revision + 1))
        insertChange(
            ProfileChangeEntity(
                factId = factId, operation = operation, oldValue = row.state, newValue = state,
                reason = reason, at = at, actor = actor,
            ),
        )
    }

    /** 彻底忘记：清空正文与出处、抹掉审计里的新旧值，并写入防复活规则。 */
    @Transaction
    open suspend fun forgetFact(factId: Long, suppress: ProfileSuppressionEntity, at: Long) {
        val row = fact(factId) ?: return
        updateFact(
            row.copy(
                value = "", state = ProfileState.FORGOTTEN, pinned = false, sensitive = true,
                updatedAt = at, revision = row.revision + 1,
            ),
        )
        deleteEvidenceRows(factId)
        scrubChanges(factId)
        addSuppression(suppress.copy(createdAt = at))
        insertChange(
            ProfileChangeEntity(factId = factId, operation = "forget", at = at, actor = ProfileOrigin.MANUAL),
        )
    }

    /** 删除事实本身（不做防复活）；审计保留改动痕迹但不含正文。 */
    @Transaction
    open suspend fun deleteFact(factId: Long, at: Long) {
        deleteEvidenceRows(factId)
        deleteFactRow(factId)
        insertChange(
            ProfileChangeEntity(factId = factId, operation = "delete", at = at, actor = ProfileOrigin.MANUAL),
        )
    }

    /** 出处失效（来源被删/改过）：全部标无效并记审计。 */
    @Transaction
    open suspend fun markEvidenceInvalid(factId: Long, at: Long) {
        invalidateEvidenceRows(factId)
        insertChange(
            ProfileChangeEntity(
                factId = factId, operation = "invalidate", at = at, actor = ProfileOrigin.SYSTEM,
            ),
        )
    }

    /** 来源被删除时调用：所有引用它的出处立即失效（不逐条改事实状态，由上层决定降级）。 */
    @Query("UPDATE profile_evidence SET valid = 0 WHERE sourceType = :type AND sourceId = :id AND valid = 1")
    abstract suspend fun invalidateSource(type: String, id: Long)

    /**
     * 清空动态记忆：删事实/出处/审计，游标归零；keepSuppression=true 时保留防复活规则。
     * 不清录音和 memory_events。
     */
    @Transaction
    open suspend fun clearAll(keepSuppression: Boolean) {
        deleteAllEvidence()
        deleteAllChanges()
        deleteAllFacts()
        resetSyncCursors()
        if (!keepSuppression) clearSuppressions()
    }

    /** 当前画像版本指纹：事实数与最后更新时间。用于丢弃基于旧画像生成的模型结果。 */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), 0) FROM profile_facts")
    abstract suspend fun profileRevision(): String

    // ---------- 来源核验（跨表只读） ----------
    /** 原始事件（含已删除行；调用方自己看 deleted）。 */
    @Query("SELECT * FROM memory_events WHERE id = :id")
    abstract suspend fun event(id: Long): MemoryEventEntity?

    /** 提取项（含已删除行）。 */
    @Query("SELECT * FROM memory_items WHERE id = :id")
    abstract suspend fun item(id: Long): MemoryItemEntity?

    @Query("SELECT * FROM transcript_lines WHERE id = :id")
    abstract suspend fun line(id: Long): TranscriptLineEntity?

    /** 该事实还有多少条出处真实存在（事件/提取项未删）且未被标无效。 */
    @Query(
        """SELECT COUNT(*) FROM profile_evidence e
           WHERE e.factId = :factId AND e.valid = 1 AND (
             (e.sourceType = 'line' AND EXISTS(SELECT 1 FROM transcript_lines l WHERE l.id = e.sourceId))
             OR (e.sourceType = 'event' AND EXISTS(SELECT 1 FROM memory_events ev WHERE ev.id = e.sourceId AND ev.deleted = 0))
             OR (e.sourceType = 'item' AND EXISTS(SELECT 1 FROM memory_items it WHERE it.id = e.sourceId AND it.deleted = 0))
           )""",
    )
    abstract suspend fun hasValidEvidence(factId: Long): Int

    /** 增量同步：按游标取新事件/新提取项。 */
    @Query("SELECT * FROM memory_events WHERE id > :cursor AND deleted = 0 ORDER BY id LIMIT :limit")
    abstract suspend fun eventsAfter(cursor: Long, limit: Int): List<MemoryEventEntity>

    @Query("SELECT * FROM memory_items WHERE id > :cursor AND deleted = 0 ORDER BY id LIMIT :limit")
    abstract suspend fun itemsAfter(cursor: Long, limit: Int): List<MemoryItemEntity>

    /** 事件下本人明确说过的话（speakerName='我' 或 speakerPersonId=0），按时间排序。 */
    @Query(
        """SELECT * FROM transcript_lines WHERE eventId IN (:eventIds)
           AND (speakerName = '我' OR speakerPersonId = 0) ORDER BY startAt, id""",
    )
    abstract suspend fun ownerLines(eventIds: List<Long>): List<TranscriptLineEntity>

    @Query("SELECT * FROM transcript_lines WHERE id IN (:ids) ORDER BY startAt, id")
    abstract suspend fun lines(ids: List<Long>): List<TranscriptLineEntity>

    /** 90 天回填：按时间窗取候选。 */
    @Query("SELECT * FROM memory_events WHERE startAt >= :from AND startAt < :to AND deleted = 0 ORDER BY startAt, id LIMIT :limit")
    abstract suspend fun eventsInRange(from: Long, to: Long, limit: Int): List<MemoryEventEntity>

    @Query("SELECT * FROM memory_items WHERE createdAt >= :from AND createdAt < :to AND deleted = 0 ORDER BY createdAt, id LIMIT :limit")
    abstract suspend fun itemsInRange(from: Long, to: Long, limit: Int): List<MemoryItemEntity>

    /** 90 天回填的可续传分页：从 afterId 之后继续。 */
    @Query("SELECT * FROM memory_events WHERE id > :afterId AND startAt >= :from AND deleted = 0 ORDER BY id LIMIT :limit")
    abstract suspend fun backfillEvents(afterId: Long, from: Long, limit: Int): List<MemoryEventEntity>

    @Query("SELECT * FROM memory_items WHERE id > :afterId AND createdAt >= :from AND deleted = 0 ORDER BY id LIMIT :limit")
    abstract suspend fun backfillItems(afterId: Long, from: Long, limit: Int): List<MemoryItemEntity>

    // ---------- 内部写语句 ----------
    @Query("DELETE FROM profile_evidence WHERE factId = :factId")
    protected abstract suspend fun deleteEvidenceRows(factId: Long)

    @Query("DELETE FROM profile_facts WHERE id = :factId")
    protected abstract suspend fun deleteFactRow(factId: Long)

    @Query("UPDATE profile_evidence SET valid = 0 WHERE factId = :factId")
    protected abstract suspend fun invalidateEvidenceRows(factId: Long)

    @Query("UPDATE profile_change_log SET oldValue = NULL, newValue = NULL WHERE factId = :factId")
    protected abstract suspend fun scrubChanges(factId: Long)

    @Query("DELETE FROM profile_evidence")
    protected abstract suspend fun deleteAllEvidence()

    @Query("DELETE FROM profile_change_log")
    protected abstract suspend fun deleteAllChanges()

    @Query("DELETE FROM profile_facts")
    protected abstract suspend fun deleteAllFacts()

    @Query(
        """UPDATE profile_sync_state SET eventCursor = 0, itemCursor = 0, backfillCursor = 0,
           backfillStartedAt = NULL, backfillDoneAt = NULL""",
    )
    protected abstract suspend fun resetSyncCursors()
}
