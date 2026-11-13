package app.resound.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 动态长期记忆的设置。全部存在既有 digest.xml 里（备份白名单已包含），key 加 profile. 前缀。
 *
 * 总开关默认关闭：关闭时不提取、不注入、不发任何新的大模型请求，但本地已有事实保留，
 * 用户仍可查看和手动维护。
 */
class ProfileSettings(context: Context) {
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 开启总开关后默认自动学习；关闭后只用已保存的背景。 */
    private val _autoLearn = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LEARN, true))
    val autoLearn: StateFlow<Boolean> = _autoLearn.asStateFlow()

    /** 开启总开关后默认在整理时附加背景。 */
    private val _injectDigest = MutableStateFlow(prefs.getBoolean(KEY_INJECT_DIGEST, true))
    val injectDigest: StateFlow<Boolean> = _injectDigest.asStateFlow()

    /** 开启总开关后默认在每日回顾时附加背景。 */
    private val _injectReview = MutableStateFlow(prefs.getBoolean(KEY_INJECT_REVIEW, true))
    val injectReview: StateFlow<Boolean> = _injectReview.asStateFlow()

    fun setEnabled(on: Boolean) = write(KEY_ENABLED, on, _enabled)
    fun setAutoLearn(on: Boolean) = write(KEY_AUTO_LEARN, on, _autoLearn)
    fun setInjectDigest(on: Boolean) = write(KEY_INJECT_DIGEST, on, _injectDigest)
    fun setInjectReview(on: Boolean) = write(KEY_INJECT_REVIEW, on, _injectReview)

    /** 是否该做任何自动/手动记忆任务（总开关 + 自动学习）。 */
    val learningAllowed: Boolean get() = _enabled.value && _autoLearn.value
    /** 是否该在整理时附加背景。 */
    val digestContextAllowed: Boolean get() = _enabled.value && _injectDigest.value
    /** 是否该在回顾时附加背景。 */
    val reviewContextAllowed: Boolean get() = _enabled.value && _injectReview.value

    /** 每天自动更新最多几次；手动“现在更新”也按此限额，避免误点反复计费。 */
    val dailyLimit = 2

    /** 两次自动更新之间至少间隔多久。 */
    val minIntervalMs: Long = TimeUnit.HOURS.toMillis(4)

    /** 今天已用完调用额度（按本地日期，和用量表同格式）。 */
    fun isQuotaReached(state: ProfileSyncStateEntity, today: String = todayKey()): Boolean =
        state.callsDay == today && state.callsCount >= dailyLimit

    /** 距上次成功更新不足最小间隔。 */
    fun tooSoon(state: ProfileSyncStateEntity, now: Long = System.currentTimeMillis()): Boolean {
        val last = state.lastSuccessAt ?: return false
        return now - last in 0 until minIntervalMs
    }

    fun todayKey(): String = LocalDate.now().toString()

    private fun write(key: String, value: Boolean, flow: MutableStateFlow<Boolean>) {
        check(prefs.edit().putBoolean(key, value).commit()) { "长期记忆设置写入失败：$key" }
        flow.value = value
    }

    companion object {
        const val KEY_ENABLED = "profile.enabled"
        const val KEY_AUTO_LEARN = "profile.autoLearn"
        const val KEY_INJECT_DIGEST = "profile.injectDigest"
        const val KEY_INJECT_REVIEW = "profile.injectReview"
    }
}
