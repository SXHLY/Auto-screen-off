package com.autoscreenoff

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import java.util.Calendar

/**
 * 息屏控制器：按条件评估是否执行「关闭应用 + 锁屏」。
 *
 * 必须【全部满足】才触发：
 * 1. 总开关开启
 * 2. 未被手动暂停（snoozeUntilMs 之前，设置页/通知栏/磁贴均可暂停）
 * 3. 屏幕当前亮着，且解锁超过宽限期（避免解锁瞬间被锁）
 * 4. 距上次触发超过冷却期（避免反复锁屏）
 * 5. 处于设定时间段内（如启用）
 * 6. 前台应用在目标列表中（可使用应用专属的无操作时长，如 B 站设 60 分钟）
 * 7. 超过该应用的无操作时长没有任何滑动/触摸
 * 8. （可选）运动检测：明显运动（拿起/走动/翻身）会阻止息屏；
 *    规律呼吸级微动（睡着握着手机）不阻止，但要求 1.5 倍无操作时间
 * 9. （可选）息屏前弹窗倒计时（秒数可配）：无人取消才由 [AppTerminator]
 *    执行 回桌面 → 锁屏 → 杀后台进程 →（可选）强力暂停；用户取消后 10 分钟内不再打扰
 */
object ScreenOffController {

    private const val TAG = "AutoScreenOff"

    /** 屏幕刚亮（刚解锁）后的宽限期 */
    private const val SCREEN_ON_GRACE_MS = 60_000L

    /** 两次自动锁定之间的最小间隔 */
    private const val LOCK_COOLDOWN_MS = 90_000L

    /** 确认弹窗显示后的防重复弹窗间隔（evaluate 每 15s 跑一次，避免重复启动） */
    private const val CONFIRM_SHOW_COOLDOWN_MS = 60_000L

    @Volatile
    private var lastLockAt = 0L

    /** 上次启动确认弹窗的时间 */
    @Volatile
    private var confirmShownAt = 0L

    /** 用户上次取消确认的时间 */
    @Volatile
    private var lastCancelAt = 0L

    /**
     * 本次触发对应的目标应用包名（evaluate 决定触发时写入）。
     * 确认弹窗倒计时结束 → [lockNow] 读取它执行「关闭应用 + 锁屏」完整链路；
     * 若为 null（如"立即测试锁定"按钮），[lockNow] 退化为仅锁屏。
     */
    @Volatile
    private var pendingPkg: String? = null

    /** 用户在确认弹窗中点了"我还醒着"（由 ConfirmOverlay 调用） */
    fun onConfirmCancelled() {
        lastCancelAt = System.currentTimeMillis()
        pendingPkg = null
        // 重置用户交互时间：重新开始计时
        MonitorAccessibilityService.lastUserInteractionAt = System.currentTimeMillis()
        Log.i(TAG, "confirm cancelled by user")
    }

    fun evaluate() {
        val service = MonitorAccessibilityService.instance ?: return
        val store = SettingsStore(service)
        if (!store.enabled) return

        val now = System.currentTimeMillis()

        // 0. 手动暂停（设置页 / 通知栏 / 磁贴均可触发）
        if (now < store.snoozeUntilMs) return

        // 0.5 驾驶模式暂停：GPS 检测到行驶且用户确认为驾驶（每 10 分钟自动复查，
        //     仍在行驶由前台服务滚动延长，见 MonitorForegroundService.drivingTick）
        if (now < store.drivingUntilMs) return

        // 1. 屏幕必须亮着
        val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return

        // 2. 刚解锁宽限期
        if (now - MonitorAccessibilityService.screenOnAt < SCREEN_ON_GRACE_MS) return

        // 3. 锁定冷却期
        if (now - lastLockAt < LOCK_COOLDOWN_MS) return

        // 4. 时间段
        if (store.windowEnabled && !isInWindow(store)) return

        // 5. 前台必须是目标应用
        val pkg = MonitorAccessibilityService.foregroundPackage ?: return
        if (!store.isTargetApp(pkg)) return

        // 6. 无操作超时（支持应用专属时长）
        val timeoutMs = store.timeoutMinutesFor(pkg) * 60_000L
        val idleMs = now - MonitorAccessibilityService.lastUserInteractionAt
        Log.d(TAG, "evaluate: pkg=$pkg timeout=${timeoutMs / 1000}s idle=${idleMs / 1000}s")
        if (idleMs < timeoutMs) return

        // 7. （可选）运动检测（行为模式分析）
        if (store.motionCheckEnabled) {
            val sinceBurst = now - MotionMonitor.lastBurstAt
            // 明显运动（拿起/走动/翻身/调整姿势）→ 用户清醒，不锁
            if (sinceBurst < timeoutMs) return
            // 只有规律呼吸级微动（如睡着握着手机/手机放胸口）→ 不阻止息屏，
            // 但要求更长的无操作时间（避免清醒但安静躺着的用户被过快锁定）
            if (MotionMonitor.hasRegularBreathingRecently()) {
                if (idleMs < (timeoutMs * 1.5).toLong()) return
            }
        }

        // 8. 用户取消确认后的冷却期（时长可在设置页配置，默认 10 分钟；
        //    用户可能在取消时清醒、稍后再睡着 —— 冷却期一过即恢复监测，可再次触发）
        if (now - lastCancelAt < store.cancelCooldownMinutes * 60_000L) return

        // 9. （可选）息屏前弹窗确认：倒计时无人取消才执行完整关闭链路
        pendingPkg = pkg
        lastLockAt = now // 提前记录，避免确认弹窗期间重复触发
        if (store.confirmBeforeLock) {
            if (now - confirmShownAt < CONFIRM_SHOW_COOLDOWN_MS) return
            confirmShownAt = now
            // 悬浮窗覆盖在所有应用之上；无悬浮窗权限时回退为直接执行
            if (!ConfirmOverlay.show(service, store.confirmSeconds)) {
                Log.w(TAG, "cannot show confirm overlay (no permission), lock directly")
                lockNow(service)
            }
            return
        }

        lockNow(service)
    }

    /**
     * 时间段判断，支持跨午夜（如 23:00 – 07:00）。
     * 开始 == 结束视为全天生效。
     */
    private fun isInWindow(store: SettingsStore): Boolean {
        val start = store.startHour
        val end = store.endHour
        if (start == end) return true
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end
        else hour >= start || hour < end
    }

    /**
     * 执行锁定，返回是否实际执行了锁屏。两种路径：
     * - 有 pendingPkg（本次评估确定的目标应用）→ 走 [AppTerminator] 完整链路：
     *   回桌面 → 锁屏 → 杀后台进程 →（可选）强力暂停；
     * - 无 pendingPkg（如「立即测试锁定」按钮）→ 仅锁屏，需设备管理员已激活。
     */
    fun lockNow(context: Context): Boolean {
        val store = SettingsStore(context)
        val pkg = pendingPkg
        if (pkg != null && store.enabled) {
            pendingPkg = null
            AppTerminator.execute(context, pkg, store)
            return true
        }

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            Log.w(TAG, "device admin not active, cannot lock")
            return false
        }
        return try {
            dpm.lockNow()
            lastLockAt = System.currentTimeMillis()
            Log.i(TAG, "screen locked by AutoScreenOff")
            true
        } catch (e: Exception) {
            Log.w(TAG, "lockNow failed", e)
            false
        }
    }
}
