package app.resound.profile

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ProfileMigrationTest {
    /** 33→34 不丢数据；新表能被 Room 校验通过，事实/出处/审计/忘记的事务都真实落库。 */
    @Test fun thirtyThreeMigratesAndFactLifecycleWorks() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "profile-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/33.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/old',10,'Done')")
            old.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model) VALUES(1,1000,2000,'对话','旧事件','摘要','[]','[]',0,1000,'test')")
            old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerName,eventId,speakerManual) VALUES(1,1,1000,2000,'我主要用 Flutter 做移动应用','我',1,0)")
            old.version = 33
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            // 打开即校验所有表、索引、外键与 Room 生成的 34 版 schema 一致
            assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version)
            assertEquals(1L, db.memory().event(1)?.id)
            val dao = db.profile()
            fun fact(value: String) = ProfileFactEntity(
                scope = "global", key = "tech.primary_mobile_stack", category = ProfileCategory.WORK,
                value = value, state = ProfileState.ACTIVE, confidence = 0.9, createdAt = 1000, updatedAt = 1000,
            )
            fun evidence(sourceId: Long) = ProfileEvidenceEntity(
                factId = 0, sourceType = ProfileSourceType.LINE, sourceId = sourceId,
                observedAt = 1000, sourceHash = "h1", addedAt = 1000,
            )

            val id = dao.createFact(
                fact("主要用 Flutter 开发移动应用"), listOf(evidence(1)),
                ProfileChangeEntity(factId = 0, operation = "create", newValue = "主要用 Flutter 开发移动应用", at = 1000, actor = ProfileOrigin.AUTO),
            )
            assertEquals(1, dao.hasValidEvidence(id))
            assertTrue(dao.profileRevision().startsWith("1:"))

            // 同一出处重复确认只留一条
            dao.confirmFact(id, listOf(evidence(1)), 1500)
            assertEquals(1, dao.evidence(id).size)

            dao.replaceFact(
                id, fact("主要用 Kotlin"), listOf(evidence(1)), 2000, reason = "本人明确说已改用 Kotlin",
            )
            assertEquals(ProfileState.SUPERSEDED, dao.fact(id)!!.state)
            assertEquals(2000L, dao.fact(id)!!.validTo)
            val active = dao.factBySlot("owner", "global", "tech.primary_mobile_stack", ProfileState.ACTIVE)!!
            assertEquals("主要用 Kotlin", active.value)

            // 来源被删：出处立刻失效
            dao.invalidateSource(ProfileSourceType.LINE, 1)
            assertEquals(0, dao.hasValidEvidence(active.id))

            // 忘记：正文清空、出处删除、审计抹掉正文、写入防复活规则
            dao.forgetFact(active.id, ProfileSuppressionEntity(scope = "global", key = "tech.primary_mobile_stack", createdAt = 3000), 3000)
            val forgotten = dao.fact(active.id)!!
            assertEquals(ProfileState.FORGOTTEN, forgotten.state)
            assertEquals("", forgotten.value)
            assertTrue(dao.evidence(active.id).isEmpty())
            assertTrue(dao.changes(active.id).none { it.oldValue != null || it.newValue != null })
            assertNotNull(dao.suppression("owner", "global", "tech.primary_mobile_stack"))

            // 清空但保留防复活规则；游标归零，当日调用额度不被清空
            dao.saveSync(ProfileSyncStateEntity(eventCursor = 9, itemCursor = 8, backfillCursor = 7, callsDay = "2026-11-13", callsCount = 2))
            dao.clearAll(keepSuppression = true)
            assertTrue(dao.facts().isEmpty())
            assertNotNull(dao.suppression("owner", "global", "tech.primary_mobile_stack"))
            assertEquals(0L, dao.sync()!!.eventCursor)
            assertEquals(0L, dao.sync()!!.backfillCursor)
            assertEquals(2, dao.sync()!!.callsCount)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    /** 设置默认全关/全开、写入即时生效，而且用的是备份白名单里的 digest.xml。 */
    @Test fun settingsDefaultOffAndPersist() {
        val context = RuntimeEnvironment.getApplication() as Context
        val settings = ProfileSettings(context)
        assertEquals(false, settings.enabled.value)
        assertEquals(true, settings.autoLearn.value)
        assertEquals(true, settings.injectDigest.value)
        assertEquals(true, settings.injectReview.value)
        assertEquals(false, settings.learningAllowed)
        settings.setEnabled(true)
        assertTrue(settings.digestContextAllowed)
        assertEquals(true, context.getSharedPreferences("digest", Context.MODE_PRIVATE).getBoolean("profile.enabled", false))
        // 回到默认关闭，避免后续测试互相影响
        settings.setEnabled(false)
        assertEquals(false, settings.enabled.value)
        assertEquals(false, settings.learningAllowed)
    }
}
