package com.autoscreenoff

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 设置存储（SharedPreferences 封装）。
 */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("autoscreenoff_settings", Context.MODE_PRIVATE)

    companion object {
        /** 内置常见视频/直播应用列表：包名 -> 显示名 */
        val DEFAULT_APPS: LinkedHashMap<String, String> = linkedMapOf(
            "com.ss.android.ugc.aweme" to "抖音",
            "com.ss.android.ugc.aweme.lite" to "抖音极速版",
            "tv.danmaku.bili" to "哔哩哔哩",
            "com.smile.gifmaker" to "快手",
            "com.kuaishou.nebula" to "快手极速版",
            "com.tencent.qqlive" to "腾讯视频",
            "com.qiyi.video" to "爱奇艺",
            "com.youku.phone" to "优酷",
            "com.hunantv.imgo.activity" to "芒果TV",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok",
            "air.tv.douyu.android" to "斗鱼",
            "com.duowan.kiwi" to "虎牙直播"
        )

        // 进程内缓存：onAccessibilityEvent 每次事件都会读 targetApps / 专属时长，
        // SharedPreferences 的 Set 引用稳定（同值返回同一实例），JSON 解析则按原始串缓存，
        // 避免高频事件下反复解析。所有实例共享，写入时同步刷新。
        @Volatile private var targetsCacheRaw: Set<String>? = null
        @Volatile private var targetsCache: Set<String> = emptySet()
        @Volatile private var timeoutsCacheRaw: String = ""
        @Volatile private var timeoutsCache: Map<String, Int> = emptyMap()
    }

    /** 总开关 */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) {
            sp.edit().putBoolean("enabled", value).apply()
        }

    /** 无操作多少分钟后息屏（全局默认，1~120 分钟） */
    var idleMinutes: Int
        get() = sp.getInt("idle_minutes", 5)
        set(value) {
            sp.edit().putInt("idle_minutes", value.coerceIn(1, 120)).apply()
        }

    /** 息屏前弹窗确认（倒计时，可取消） */
    var confirmBeforeLock: Boolean
        get() = sp.getBoolean("confirm_before_lock", true)
        set(value) {
            sp.edit().putBoolean("confirm_before_lock", value).apply()
        }

    /** 静止检测开关：手机需保持静止才息屏 */
    var motionCheckEnabled: Boolean
        get() = sp.getBoolean("motion_check", true)
        set(value) {
            sp.edit().putBoolean("motion_check", value).apply()
        }

    /** 时间段开关 */
    var windowEnabled: Boolean
        get() = sp.getBoolean("window_enabled", false)
        set(value) {
            sp.edit().putBoolean("window_enabled", value).apply()
        }

    /** 时间段开始小时（默认 23 点） */
    var startHour: Int
        get() = sp.getInt("start_hour", 23)
        set(value) {
            sp.edit().putInt("start_hour", value.coerceIn(0, 23)).apply()
        }

    /** 时间段结束小时（默认 7 点，支持跨午夜） */
    var endHour: Int
        get() = sp.getInt("end_hour", 7)
        set(value) {
            sp.edit().putInt("end_hour", value.coerceIn(0, 23)).apply()
        }

    /** 用户手动添加的包名 */
    var customApps: Set<String>
        get() = sp.getStringSet("custom_apps", emptySet()) ?: emptySet()
        set(value) {
            sp.edit().putStringSet("custom_apps", value).apply()
        }

    /** 当前生效的目标应用集合（带进程内缓存；返回只读副本，调用方不可修改） */
    var targetApps: Set<String>
        get() {
            val raw = sp.getStringSet("target_apps", DEFAULT_APPS.keys) ?: DEFAULT_APPS.keys
            if (raw !== targetsCacheRaw) {
                targetsCache = raw.toSet()
                targetsCacheRaw = raw
            }
            return targetsCache
        }
        set(value) {
            sp.edit().putStringSet("target_apps", value).apply()
            targetsCache = value
            targetsCacheRaw = value
        }

    fun isTargetApp(pkg: String): Boolean = pkg in targetApps

    /**
     * 应用专属无操作时长（分钟）：包名 -> 分钟。
     * 用 JSON 字符串序列化存储（SharedPreferences 无 Map 类型），解析结果按原始串缓存。
     */
    fun getAppTimeoutMinutes(): Map<String, Int> {
        val raw = sp.getString("app_timeout_minutes", "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        if (raw == timeoutsCacheRaw) return timeoutsCache
        val map = try {
            val obj = JSONObject(raw)
            val map = HashMap<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optInt(k, 0)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
        timeoutsCacheRaw = raw
        timeoutsCache = map
        return map
    }

    fun setAppTimeoutMinutes(map: Map<String, Int>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        val raw = obj.toString()
        sp.edit().putString("app_timeout_minutes", raw).apply()
        timeoutsCacheRaw = raw
        timeoutsCache = map.toMap()
    }

    /** 某应用的无操作超时（分钟）：有专属设置用专属值，否则用全局默认 */
    fun timeoutMinutesFor(pkg: String): Int {
        return getAppTimeoutMinutes()[pkg] ?: idleMinutes
    }

    /** 息屏前确认弹窗倒计时（秒，5~30，默认 10） */
    var confirmSeconds: Int
        get() = sp.getInt("confirm_seconds", 10)
        set(value) {
            sp.edit().putInt("confirm_seconds", value.coerceIn(5, 30)).apply()
        }

    /**
     * 锁屏后关闭视频应用（默认开）：
     * 锁屏后目标应用已变为后台进程，调用 killBackgroundProcesses 彻底结束，
     * 停止后台音频/预加载/推送唤醒。
     */
    var closeAppEnabled: Boolean
        get() = sp.getBoolean("close_app", true)
        set(value) {
            sp.edit().putBoolean("close_app", value).apply()
        }

    /**
     * 强力暂停模式（默认关）：设备管理员 setPackagesSuspended 冻结目标应用全部组件。
     * 比普通杀进程更彻底，但被暂停期间应用完全无法启动，
     * 用户解锁屏幕后自动恢复（见 AppTerminator.resumeSuspendedApps）。
     */
    var suspendAppEnabled: Boolean
        get() = sp.getBoolean("suspend_app", false)
        set(value) {
            sp.edit().putBoolean("suspend_app", value).apply()
        }

    /** 手动暂停监测的截止时间戳（0 = 未暂停），见 MainActivity 的暂停卡片 */
    var snoozeUntilMs: Long
        get() = sp.getLong("snooze_until", 0L)
        set(value) {
            sp.edit().putLong("snooze_until", value).apply()
        }

    /**
     * 「我还醒着」/倒计时运动取消后，重新开始监测的延迟（分钟，默认 10）。
     * 用户可能在取消时还清醒、稍后才睡着 —— 冷却期一过即恢复完整监测。
     */
    var cancelCooldownMinutes: Int
        get() = sp.getInt("cancel_cooldown_minutes", 10)
        set(value) {
            sp.edit().putInt("cancel_cooldown_minutes", value.coerceIn(1, 240)).apply()
        }

    /** 驾驶模式检测开关（GPS 速度检测，默认关；需定位权限） */
    var drivingCheckEnabled: Boolean
        get() = sp.getBoolean("driving_check", false)
        set(value) {
            sp.edit().putBoolean("driving_check", value).apply()
        }

    /** 驾驶模式暂停截止时间戳（0 = 未生效；到期自动复查，仍在行驶则滚动延长） */
    var drivingUntilMs: Long
        get() = sp.getLong("driving_until", 0L)
        set(value) {
            sp.edit().putLong("driving_until", value).apply()
        }

    /** 「我是乘客」选择后的免打扰截止时间戳（此期间不再弹驾驶提醒） */
    var passengerUntilMs: Long
        get() = sp.getLong("passenger_until", 0L)
        set(value) {
            sp.edit().putLong("passenger_until", value).apply()
        }

    // ------------------------------------------------------------------
    // 仓库监听（同步更新）
    // ------------------------------------------------------------------

    /** 更新仓库地址：github.com/owner/repo、gitee.com/owner/repo 或完整 API 地址；空=不检查 */
    var updateRepoUrl: String
        get() = sp.getString("update_repo_url", "") ?: ""
        set(value) {
            sp.edit().putString("update_repo_url", value.trim()).apply()
        }

    /** 已发现待处理的新版本号（空=无）；通知栏「下载」动作从这里取地址 */
    var pendingUpdateVersion: String
        get() = sp.getString("pending_update_version", "") ?: ""
        set(value) {
            sp.edit().putString("pending_update_version", value).apply()
        }

    var pendingUpdateUrl: String
        get() = sp.getString("pending_update_url", "") ?: ""
        set(value) {
            sp.edit().putString("pending_update_url", value).apply()
        }

    /** 当前被强力暂停的应用包名集合（解锁屏幕时自动恢复并清空） */
    var pausedApps: Set<String>
        get() = sp.getStringSet("paused_apps", emptySet()) ?: emptySet()
        set(value) {
            sp.edit().putStringSet("paused_apps", value).apply()
        }
}
