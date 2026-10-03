package com.autoscreenoff

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务 —— 感知核心，负责：
 * 1. 记录前台应用包名（TYPE_WINDOW_STATE_CHANGED）
 * 2. 记录用户滑动/触摸时间（TYPE_VIEW_SCROLLED / TYPE_TOUCH_INTERACTION_START）
 * 3. 监听屏幕亮/灭广播
 * 4. 挂载加速度传感器（MotionMonitor），并周期性评估是否满足息屏条件
 *
 * 注意：视频播放时的进度条刷新、弹幕等属于 TYPE_WINDOW_CONTENT_CHANGED，
 * 会被忽略，不视为"用户操作"。
 */
class MonitorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoScreenOff"

        /** 周期性兜底评估间隔 */
        private const val EVALUATE_INTERVAL_MS = 15_000L

        /** 服务被销毁后延迟确认关闭原因的时间 */
        private const val DISABLE_CHECK_DELAY_MS = 2_500L

        /** 提醒通知的渠道与 id */
        private const val CHANNEL_ID = "permission_alert"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: MonitorAccessibilityService? = null
            private set

        /** 当前前台应用包名 */
        @Volatile
        var foregroundPackage: String? = null

        /** 用户最后一次滑动/触摸的时间戳 */
        @Volatile
        var lastUserInteractionAt: Long = 0L

        /** 屏幕最近一次亮起的时间戳 */
        @Volatile
        var screenOnAt: Long = 0L

        /** 屏幕最近一次熄灭的时间戳 */
        @Volatile
        var screenOffAt: Long = 0L

        fun isScreenOn(): Boolean = screenOffAt < screenOnAt
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 高频事件里复用的设置读取器（SharedPreferences 本身有内存缓存） */
    private val settings by lazy { SettingsStore(this) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnAt = System.currentTimeMillis()
                    Log.i(TAG, "screen on")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = System.currentTimeMillis()
                    // 用户主动按电源键息屏 → 关闭确认悬浮窗，不打扰
                    ConfirmOverlay.dismiss()
                    Log.i(TAG, "screen off")
                }
                Intent.ACTION_USER_PRESENT -> {
                    // 用户解锁屏幕（睡醒了）→ 恢复所有被强力暂停的视频应用
                    context?.let { AppTerminator.resumeSuspendedApps(it) }
                }
            }
        }
    }

    /** 周期性评估：即使没有任何无障碍事件也兜底检查（防止漏触发） */
    private val evaluateTick = object : Runnable {
        override fun run() {
            ScreenOffController.evaluate()
            handler.postDelayed(this, EVALUATE_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val now = System.currentTimeMillis()
        screenOnAt = now
        lastUserInteractionAt = now
        MotionMonitor.start(this)
        // 与前台服务互相保活：无障碍服务运行时确保常驻通知服务已启动
        MonitorForegroundService.ensureRunning(this)
        try {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed", e)
        }
        handler.post(evaluateTick)
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { pkg ->
                    // 确认悬浮窗自身的窗口事件不更新前台包名：悬浮窗不是"用户正在使用的
                    // 应用"，且它消失时不会再发窗口事件，若让它写入前台包名会导致
                    // 前台记录停留在本应用包名、目标应用在前台也永不触发。
                    if (pkg == packageName && ConfirmOverlay.isShowing) return
                    if (pkg != foregroundPackage) {
                        foregroundPackage = pkg
                        Log.i(TAG, "foreground -> $pkg")
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // 用户在滑动/触摸屏幕 → 仍在清醒操作，刷新最后操作时间
                lastUserInteractionAt = System.currentTimeMillis()
            }

            // 视频进度、弹幕等自动变化：不算用户操作。
            // 但若事件来自目标应用，说明该应用窗口仍在前台可见 —— 用它兜底修正前台包名
            // （修复：解锁动画/进程重启竞态会丢掉 WINDOW_STATE_CHANGED，导致前台包名
            //  停留在桌面，目标应用明明在前台却永不触发）。
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != foregroundPackage &&
                    settings.isTargetApp(pkg)
                ) {
                    foregroundPackage = pkg
                    Log.i(TAG, "foreground (content fallback) -> $pkg")
                } else {
                    return // 非目标应用的内容变化（桌面/系统 UI 刷新）无需评估
                }
            }

            // 其它事件类型（未订阅，理论上不会到达）：忽略
            else -> return
        }
        ScreenOffController.evaluate()
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        MotionMonitor.stop()
        // 服务可能带着已显示的确认悬浮窗一起被系统回收 → 必须一并移除，避免窗口泄漏
        ConfirmOverlay.dismiss()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        // 延迟确认：如果是用户关闭了无障碍（服务没有重新连接、也不再在启用列表中），
        // 且总开关仍开启 → 发送提醒通知，避免功能被关闭后用户毫无察觉
        handler.postDelayed({
            if (instance == null && !isStillEnabledBySystem()) {
                notifyAccessibilityDisabled()
            }
        }, DISABLE_CHECK_DELAY_MS)
        super.onDestroy()
    }

    /** 检查系统设置中本服务是否仍处于启用状态 */
    private fun isStillEnabledBySystem(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si?.packageName == packageName &&
                        si?.name?.endsWith("MonitorAccessibilityService") == true
                }
        } catch (e: Exception) {
            false
        }
    }

    /** 发送"无障碍已被关闭"提醒通知（点击跳转无障碍设置页） */
    private fun notifyAccessibilityDisabled() {
        if (!SettingsStore(this).enabled) return // 总开关未开启则不打扰
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "权限提醒",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("自动息屏已停止工作")
                .setContentText("无障碍服务已被关闭，自动息屏将不会触发，点击重新开启")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "accessibility disabled notification sent")
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }
}
