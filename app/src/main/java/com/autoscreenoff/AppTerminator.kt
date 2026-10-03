package com.autoscreenoff

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 应用终结器：满足触发条件后执行「关闭视频 App + 息屏」完整链路。
 *
 * 执行顺序（先退前台 → 再锁屏 → 最后杀后台），每一步都独立容错：
 *
 * 1. GLOBAL_ACTION_HOME —— 通过无障碍服务把目标应用退回桌面，立即停止前台播放。
 *    锁屏前先做这一步，是因为普通应用无法结束其他应用的"前台进程"（系统限制），
 *    但把应用退回桌面后它就变成了后台进程，第 3 步就能合法结束它。
 *
 * 2. DevicePolicyManager.lockNow() —— 设备管理员立即息屏锁屏。
 *    若设备管理员未激活则跳过（仅靠第 1、3 步关闭应用），[ScreenOffController]
 *    下次评估时仍会继续尝试锁屏。
 *
 * 3. killBackgroundProcesses(pkg) —— 锁屏后延迟 800ms 执行（等系统完成前后台切换），
 *    结束目标应用的后台进程，停止后台音频、预加载与长连接。
 *    该调用只能作用于"后台进程"，不能杀前台进程 —— 这是 Android 对普通应用的硬限制，
 *    因此必须先锁屏/回桌面，再执行本步，顺序不可调换。
 *
 * 4. （可选，默认关）setPackagesSuspended() —— 设备管理员把目标应用置为"已暂停"状态，
 *    完全冻结其全部组件（比杀进程更彻底，连启动器入口都会显示"已暂停"）。
 *    用户解锁屏幕时由 [MonitorAccessibilityService] 自动恢复
 *    （见 [resumeSuspendedApps]），避免醒来后应用打不开。
 */
object AppTerminator {

    private const val TAG = "AutoScreenOff"

    /** 锁屏后延迟杀后台的等待时间（等系统完成前后台切换） */
    private const val KILL_DELAY_MS = 800L

    private val handler = Handler(Looper.getMainLooper())

    /** 满足条件后执行完整关闭链路 */
    fun execute(context: Context, pkg: String, store: SettingsStore) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        val adminActive = dpm.isAdminActive(admin)

        // 1. 目标应用退回桌面（停止前台播放；无障碍服务不可用时跳过）
        try {
            MonitorAccessibilityService.instance
                ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.w(TAG, "go home failed", e)
        }

        // 2. 立即锁屏（优先；无设备管理员时仍执行关闭，靠第 1/3 步兜底）
        if (adminActive) {
            try {
                dpm.lockNow()
                Log.i(TAG, "screen locked, closing $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "lockNow failed", e)
            }
        } else {
            Log.w(TAG, "device admin not active, lock skipped")
        }

        // 3. 锁屏后目标应用已是后台进程 → 彻底关闭（停止后台播放/加载）
        if (store.closeAppEnabled) {
            handler.postDelayed({
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.killBackgroundProcesses(pkg)
                    Log.i(TAG, "background processes of $pkg killed")
                } catch (e: Exception) {
                    Log.w(TAG, "killBackgroundProcesses failed", e)
                }
            }, KILL_DELAY_MS)
        }

        // 4. （可选）设备所有者强力暂停：冻结应用全部组件，解锁后自动恢复。
        //    setPackagesSuspended 仅设备/配置文件所有者可调用——普通手机上用户只能
        //    激活普通设备管理员，调用会被系统拒绝；设置页已据此禁用该模式，
        //    这里再兜底跳过（防止所有者身份被撤销后残留的开关状态触发无效调用）。
        if (store.suspendAppEnabled && adminActive && canSuspend(context)) {
            try {
                // 显式使用三参版本（API 24 起，minSdk 26 原生可用）
                dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
                store.pausedApps = store.pausedApps + pkg
                Log.i(TAG, "$pkg suspended")
            } catch (e: Exception) {
                Log.w(TAG, "suspend failed (unsupported on this device?)", e)
            }
        }
    }

    /**
     * setPackagesSuspended 仅设备/配置文件所有者可调用。
     * 普通手机无法成为设备所有者（需企业部署或 ADB 预置），用户只能激活普通
     * 设备管理员——因此该能力需要先检测，不可用时在设置页如实禁用。
     */
    fun canSuspend(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return try {
            @Suppress("DEPRECATION")
            dpm.isDeviceOwnerApp(context.packageName) ||
                dpm.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** 用户解锁屏幕（ACTION_USER_PRESENT）后恢复所有被强力暂停的应用 */
    fun resumeSuspendedApps(context: Context) {
        val store = SettingsStore(context)
        val paused = store.pausedApps
        if (paused.isEmpty()) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        try {
            dpm.setPackagesSuspended(admin, paused.toTypedArray(), false)
            Log.i(TAG, "resumed: $paused")
        } catch (e: Exception) {
            Log.w(TAG, "resume suspended apps failed", e)
        }
        store.pausedApps = emptySet()
    }
}
