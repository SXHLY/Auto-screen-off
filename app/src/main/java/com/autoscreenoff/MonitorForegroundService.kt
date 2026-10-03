package com.autoscreenoff

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 常驻前台服务：保活载体 + 运行状态通知 + 一键暂停入口。
 *
 * - Android 14（API 34，targetSdk 34）强制要求：声明 `foregroundServiceType="specialUse"`
 *   + 权限 FOREGROUND_SERVICE + FOREGRound_SERVICE_SPECIAL_USE，并在 manifest 中
 *   用 PROPERTY_SPECIAL_USE_FGS_SUBTYPE 说明用途，否则 startForegroundService 抛异常。
 *
 * - 与无障碍服务互相保活：
 *   · 无障碍服务连接时（onServiceConnected）调用 [ensureRunning] 拉起本服务；
 *   · 本服务每 60s 自检一次无障碍是否仍被系统启用，被停用时通知文案变为红色告警，
 *     引导用户回设置页重新开启（部分国产 ROM 会静默杀掉无障碍服务）。
 *
 * - START_STICKY：被系统回收后会尝试重建（Android 8+ 前台服务存活优先级
 *   显著高于纯后台进程；对省电策略/清理软件无银弹，见 README「后台保活」章节）。
 */
class MonitorForegroundService : Service() {

    companion object {
        private const val TAG = "AutoScreenOff"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_SNOOZE = "com.autoscreenoff.action.SNOOZE"
        private const val ACTION_RESUME = "com.autoscreenoff.action.RESUME"
        private const val ACTION_DRIVING_CONFIRM = "com.autoscreenoff.action.DRIVING_CONFIRM"
        private const val ACTION_PASSENGER = "com.autoscreenoff.action.PASSENGER"

        /** 驾驶提醒的渠道与 id */
        private const val DRIVING_CHANNEL_ID = "driving"
        private const val DRIVING_NOTIFICATION_ID = 3

        /** 自检间隔：低频，避免唤醒开销 */
        private const val CHECK_INTERVAL_MS = 60_000L

        /** 通知栏一键暂停的时长 */
        private const val SNOOZE_MINUTES = 30L

        /** 上次弹驾驶提醒的时间（进程内即可） */
        @Volatile
        private var lastDrivingPromptAt = 0L

        @Volatile
        var running: Boolean = false
            private set

        /** 总开关开启时确保前台服务在运行（无障碍服务连接 / 开机 / 设置页开启时调用） */
        fun ensureRunning(context: Context) {
            if (!SettingsStore(context).enabled) return
            try {
                context.startForegroundService(
                    Intent(context, MonitorForegroundService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "start foreground service failed", e)
            }
        }

        /** 总开关关闭时停止前台服务 */
        fun stopIfDisabled(context: Context) {
            context.stopService(Intent(context, MonitorForegroundService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val checkTick = object : Runnable {
        override fun run() {
            drivingTick()
            updateNotification()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        UpdateManager.createChannel(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        when (intent?.action) {
            ACTION_SNOOZE -> {
                store.snoozeUntilMs = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
                Log.i(TAG, "snoozed ${SNOOZE_MINUTES}min from notification")
            }
            ACTION_RESUME -> {
                store.snoozeUntilMs = 0L
                Log.i(TAG, "resumed from notification")
            }
            ACTION_DRIVING_CONFIRM -> {
                cancelDrivingNotification()
                store.drivingUntilMs = System.currentTimeMillis() + DrivingMonitor.DRIVING_SNOOZE_MS
                lastDrivingPromptAt = System.currentTimeMillis()
                Log.i(TAG, "driving mode confirmed, paused ${DrivingMonitor.DRIVING_SNOOZE_MS / 60000}min")
            }
            ACTION_PASSENGER -> {
                cancelDrivingNotification()
                store.drivingUntilMs = 0L
                store.passengerUntilMs = System.currentTimeMillis() + DrivingMonitor.PASSENGER_SUPPRESS_MS
                Log.i(TAG, "passenger mode confirmed, keep monitoring")
            }
            // 更新通知栏的「下载」按钮：下载已发现的新版本 APK
            UpdateManager.ACTION_DOWNLOAD -> {
                val v = store.pendingUpdateVersion
                val u = store.pendingUpdateUrl
                if (v.isNotEmpty() && u.isNotEmpty()) {
                    UpdateManager.download(this, u, v)
                }
            }
        }

        // 驾驶检测开启且已授权 → 同时监听网络切换（GPS 不可用时的降级信号）
        if (drivingAvailable()) {
            DrivingMonitor.startNetworkWatch(this)
        } else {
            DrivingMonitor.stopNetworkWatch()
        }

        startForegroundCompat()
        handler.removeCallbacks(checkTick)
        handler.post(checkTick)
        return START_STICKY
    }

    /** 驾驶检测可用：开关开启 + 定位权限已授予 */
    private fun drivingAvailable(): Boolean {
        return SettingsStore(this).drivingCheckEnabled &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 前台服务类型：specialUse 必需；驾驶检测可用时叠加 location
     * （Android 14 起从前台服务访问定位必须声明 location 类型）。
     * API 34 以下系统按 Manifest 声明的类型集处理。
     */
    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (drivingAvailable()) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            t
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Exception) {
            // 后台启动（开机自启）可能不允许 location 类型 → 退回 specialUse
            Log.w(TAG, "startForeground with type=$type failed, fallback", e)
            val fallback = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fallback)
        }
    }

    /**
     * 驾驶检测状态机（每 60s）：
     * - 驾驶模式生效中：仍检测到行驶 → 滚动延长 10 分钟；无信号则到期自然恢复监测
     * - 未生效：检测到行驶且不在乘客免打扰/提醒冷却期 → 弹「驾驶/乘客」选择通知
     */
    private fun drivingTick() {
        val store = SettingsStore(this)
        if (!drivingAvailable()) return
        val now = System.currentTimeMillis()
        val driving = DrivingMonitor.isLikelyDriving(this)
        if (now < store.drivingUntilMs) {
            if (driving) {
                store.drivingUntilMs = now + DrivingMonitor.DRIVING_SNOOZE_MS
            }
        } else if (driving &&
            now >= store.passengerUntilMs &&
            now - lastDrivingPromptAt >= DrivingMonitor.PROMPT_COOLDOWN_MS
        ) {
            lastDrivingPromptAt = now
            showDrivingPrompt()
        }
    }

    /** 弹「驾驶/乘客」选择通知（高优先级横幅） */
    private fun showDrivingPrompt() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    DRIVING_CHANNEL_ID, "驾驶提醒", NotificationManager.IMPORTANCE_HIGH
                )
            )
            val drivePi = PendingIntent.getService(
                this, 3,
                Intent(this, MonitorForegroundService::class.java).setAction(ACTION_DRIVING_CONFIRM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val passengerPi = PendingIntent.getService(
                this, 4,
                Intent(this, MonitorForegroundService::class.java).setAction(ACTION_PASSENGER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val openPi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = Notification.Builder(this, DRIVING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_motion)
                .setContentTitle("检测到持续移动")
                .setContentText("您可能在驾驶。驾驶模式将暂停自动息屏 10 分钟并自动复查")
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .addAction(Notification.Action.Builder(null, "驾驶模式", drivePi).build())
                .addAction(Notification.Action.Builder(null, "我是乘客", passengerPi).build())
                .build()
            nm.notify(DRIVING_NOTIFICATION_ID, notification)
            Log.i(TAG, "driving prompt shown")
        } catch (e: Exception) {
            Log.w(TAG, "driving prompt failed", e)
        }
    }

    private fun cancelDrivingNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(DRIVING_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        DrivingMonitor.stopNetworkWatch()
        super.onDestroy()
        // 总开关仍开启时被销毁（省电策略/清理软件）→ 尝试重启自己。
        // 前台服务被 force-stop 后系统不会立即重建，但 START_STICKY +
        // 无障碍服务的 ensureRunning 双通道能覆盖大多数场景。
        if (SettingsStore(this).enabled) {
            try {
                startService(Intent(this, MonitorForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "self restart failed", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "运行状态", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** 无障碍服务是否仍被系统启用（部分 ROM 会静默关闭） */
    private fun isAccessibilityEnabled(): Boolean {
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

    private fun buildNotification(): Notification {
        val store = SettingsStore(this)
        val now = System.currentTimeMillis()
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val snoozed = store.snoozeUntilMs > now
        val driving = store.drivingUntilMs > now
        val text = when {
            !store.enabled -> "总开关已关闭，点击打开设置"
            driving -> "驾驶模式生效中，监测已暂停（每 10 分钟自动复查）"
            snoozed -> "已暂停至 ${formatTime(store.snoozeUntilMs)}"
            !isAccessibilityEnabled() -> "⚠ 无障碍服务已停用，点击重新开启"
            else -> "监测中：${store.targetApps.size} 个目标应用"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("自动息屏")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
        if (store.enabled && !snoozed) {
            val snoozePi = PendingIntent.getService(
                this, 1,
                Intent(this, MonitorForegroundService::class.java).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                Notification.Action.Builder(null, "暂停 30 分钟", snoozePi).build()
            )
        }
        if (snoozed) {
            val resumePi = PendingIntent.getService(
                this, 2,
                Intent(this, MonitorForegroundService::class.java).setAction(ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                Notification.Action.Builder(null, "恢复监测", resumePi).build()
            )
        }
        return builder.build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "update notification failed", e)
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
}
