package app.resound.profile

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 动态长期记忆（Living Memory）的数据层。
 *
 * 与 memory_events（录音整理出来的事件记忆）彻底分开：这里存的是从记录里提炼、
 * 可被用户修改和固定的“当前有效个人背景”。LLM 只提议候选，由 Kotlin 审批后写库。
 */
@Entity(
    tableName = "profile_facts",
    indices = [Index(value = ["subject", "scope", "key"]), Index("state"), Index("updatedAt")],
)
data class ProfileFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String = "owner",
    val scope: String, // global 或 project:<id>
    val key: String, // 见 ProfileKeys
    val category: String, // identity/family/work/project/preference/other
    val value: String,
    val state: String, // active/pending_review/superseded/disabled/forgotten
    val confidence: Double = 0.0,
    val origin: String = "auto", // auto/manual
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sensitive: Boolean = false,
    val validFrom: Long? = null,
    val validTo: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    val lastConfirmedAt: Long? = null,
)

/** 事实的出处。必须指向真实存在、未被删除的来源；excerpt 只是短摘录，不替代 sourceId。 */
@Entity(
    tableName = "profile_evidence",
    indices = [Index("factId"), Index(value = ["factId", "sourceType", "sourceId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = ProfileFactEntity::class, parentColumns = ["id"], childColumns = ["factId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ProfileEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val factId: Long,
    val sourceType: String, // line/event/item
    val sourceId: Long,
    val observedAt: Long,
    val sourceHash: String,
    val excerpt: String? = null, // 短摘录，可选，不替代 sourceId
    val addedAt: Long,
    @ColumnInfo(defaultValue = "1") val valid: Boolean = true,
)

/** 审计历史：何时、因为什么、由谁改了哪条事实；“忘记”后不留正文。 */
@Entity(tableName = "profile_change_log", indices = [Index("factId"), Index("at")])
data class ProfileChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val factId: Long,
    val operation: String, // create/update/confirm/supersede/review/disable/enable/delete/forget/invalidate/merge
    val oldValue: String? = null,
    val newValue: String? = null,
    val reason: String? = null,
    val at: Long,
    val actor: String, // auto/manual/system
)

/** 增量游标与限流。水位只在结果合法且写入成功之后提交。 */
@Entity(tableName = "profile_sync_state")
data class ProfileSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = "0") val eventCursor: Long = 0,
    @ColumnInfo(defaultValue = "0") val itemCursor: Long = 0,
    @ColumnInfo(defaultValue = "0") val backfillCursor: Long = 0,
    val backfillStartedAt: Long? = null,
    val backfillDoneAt: Long? = null,
    val lastScanAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
    val callsDay: String? = null,
    @ColumnInfo(defaultValue = "0") val callsCount: Int = 0,
    @ColumnInfo(defaultValue = "1") val rulesVersion: Int = 1,
)

/** 用户“忘记”过的槽位：防止下一轮读同一来源又自动建回来。不含原话正文。 */
@Entity(
    tableName = "profile_suppression",
    indices = [Index(value = ["subject", "scope", "key"], unique = true)],
)
data class ProfileSuppressionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String = "owner",
    val scope: String,
    val key: String,
    val valueHash: String? = null,
    val reason: String? = null,
    val createdAt: Long,
)

object ProfileState {
    const val ACTIVE = "active"
    const val PENDING = "pending_review"
    const val SUPERSEDED = "superseded"
    const val DISABLED = "disabled"
    const val FORGOTTEN = "forgotten"
    val all = listOf(ACTIVE, PENDING, SUPERSEDED, DISABLED, FORGOTTEN)
}

object ProfileOrigin {
    const val AUTO = "auto"
    const val MANUAL = "manual"
    const val SYSTEM = "system"
    val all = listOf(AUTO, MANUAL, SYSTEM)
}

object ProfileCategory {
    const val IDENTITY = "identity"
    const val FAMILY = "family"
    const val WORK = "work"
    const val PROJECT = "project"
    const val PREFERENCE = "preference"
    const val OTHER = "other"
    val all = listOf(IDENTITY, FAMILY, WORK, PROJECT, PREFERENCE, OTHER)
}

/** 来源类型的白名单；未知类型一律拒绝。 */
object ProfileSourceType {
    const val LINE = "line"
    const val EVENT = "event"
    const val ITEM = "item"
    val all = listOf(LINE, EVENT, ITEM)
}

/** 手写建表 SQL：必须与上面的实体完全一致（Room 迁移校验按列名/类型/非空/默认值比对）。 */
internal object ProfileSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `profile_facts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `subject` TEXT NOT NULL, `scope` TEXT NOT NULL, `key` TEXT NOT NULL, `category` TEXT NOT NULL,
                `value` TEXT NOT NULL, `state` TEXT NOT NULL, `confidence` REAL NOT NULL, `origin` TEXT NOT NULL,
                `pinned` INTEGER NOT NULL DEFAULT 0, `sensitive` INTEGER NOT NULL DEFAULT 0,
                `validFrom` INTEGER, `validTo` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL DEFAULT 1, `lastConfirmedAt` INTEGER)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_facts_subject_scope_key` ON `profile_facts` (`subject`, `scope`, `key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_facts_state` ON `profile_facts` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_facts_updatedAt` ON `profile_facts` (`updatedAt`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `profile_evidence` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `factId` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `sourceId` INTEGER NOT NULL,
                `observedAt` INTEGER NOT NULL, `sourceHash` TEXT NOT NULL, `excerpt` TEXT,
                `addedAt` INTEGER NOT NULL, `valid` INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(`factId`) REFERENCES `profile_facts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_evidence_factId` ON `profile_evidence` (`factId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_evidence_factId_sourceType_sourceId` ON `profile_evidence` (`factId`, `sourceType`, `sourceId`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `profile_change_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `factId` INTEGER NOT NULL, `operation` TEXT NOT NULL, `oldValue` TEXT, `newValue` TEXT,
                `reason` TEXT, `at` INTEGER NOT NULL, `actor` TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_change_log_factId` ON `profile_change_log` (`factId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_profile_change_log_at` ON `profile_change_log` (`at`)")

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `profile_sync_state` (`id` INTEGER NOT NULL,
                `eventCursor` INTEGER NOT NULL DEFAULT 0, `itemCursor` INTEGER NOT NULL DEFAULT 0,
                `backfillCursor` INTEGER NOT NULL DEFAULT 0, `backfillStartedAt` INTEGER, `backfillDoneAt` INTEGER,
                `lastScanAt` INTEGER, `lastSuccessAt` INTEGER, `lastError` TEXT, `callsDay` TEXT,
                `callsCount` INTEGER NOT NULL DEFAULT 0, `rulesVersion` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))""",
        )

        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `profile_suppression` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `subject` TEXT NOT NULL, `scope` TEXT NOT NULL, `key` TEXT NOT NULL, `valueHash` TEXT,
                `reason` TEXT, `createdAt` INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_profile_suppression_subject_scope_key` ON `profile_suppression` (`subject`, `scope`, `key`)")
    }
}
