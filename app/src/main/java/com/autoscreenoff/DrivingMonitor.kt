package com.autoscreenoff

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * 驾驶检测（可选功能，默认关）。
 *
 * - 主信号：GPS 最后已知定位的速度 ≥25 km/h（步行/跑步 ≤20 km/h，不会误报）。
 *   只读系统已有的定位样本（[LocationManager.getLastKnownLocation]），不主动发起
 *   GPS 请求，电量开销可忽略；行驶中导航类应用会持续产生新鲜定位样本。
 * - 降级信号（GPS 不可用/无样本时）：近期发生 WiFi↔蜂窝 网络切换。
 *   行驶中手机会离开家庭 WiFi、在基站间切换，网络切换与"公网 IP 变化"等价，
 *   但不需要 INTERNET 权限（本应用承诺零联网，仅监听系统网络状态回调）。
 *
 * 检测到疑似行驶 → 由 [MonitorForegroundService] 弹通知让用户选择：
 * 「驾驶模式」（暂停监测 10 分钟，到期自动复查，仍在行驶则滚动延长）/
 * 「我是乘客」（继续监测，1 小时内不再打扰）。
 */
object DrivingMonitor {

    private const val TAG = "AutoScreenOff"

    /** 判定为行驶的速度阈值（m/s，约 25 km/h；排除步行/跑步/骑行慢速） */
    const val SPEED_THRESHOLD_MPS = 7.0

    /** 定位样本视为新鲜的时限（超龄样本不可信） */
    private const val LOCATION_FRESH_MS = 120_000L

    /** 驾驶模式单次暂停时长（到期自动复查，仍在行驶则滚动延长） */
    const val DRIVING_SNOOZE_MS = 10 * 60_000L

    /** 「我是乘客」后的免打扰时长 */
    const val PASSENGER_SUPPRESS_MS = 60 * 60_000L

    /** 两次驾驶提醒的最小间隔（与单次暂停时长一致：到期仍在行驶则再次提醒） */
    const val PROMPT_COOLDOWN_MS = 10 * 60_000L

    /** 网络切换视为「可能在移动」的窗口 */
    private const val NETWORK_SWITCH_WINDOW_MS = 10 * 60_000L

    @Volatile
    var lastNetworkSwitchAt = 0L
        private set

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 最近一次定位的速度（m/s）；无权限/无新鲜样本返回 null */
    fun currentSpeedMps(context: Context): Double? {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            if (loc != null && loc.hasSpeed() &&
                System.currentTimeMillis() - loc.time < LOCATION_FRESH_MS
            ) loc.speed.toDouble() else null
        } catch (e: Exception) {
            null
        }
    }

    /** 是否疑似在行驶（主信号 GPS 速度；降级信号近期网络切换） */
    fun isLikelyDriving(context: Context): Boolean {
        val speed = currentSpeedMps(context)
        if (speed != null) {
            Log.d(TAG, "driving check: speed=%.1f m/s".format(speed))
            return speed >= SPEED_THRESHOLD_MPS
        }
        val switched = System.currentTimeMillis() - lastNetworkSwitchAt < NETWORK_SWITCH_WINDOW_MS
        if (switched) Log.d(TAG, "driving check: no fresh gps, recent network switch")
        return switched
    }

    /** 监听系统默认网络切换（WiFi ↔ 蜂窝），作为 GPS 不可用时的移动降级信号 */
    fun startNetworkWatch(context: Context) {
        if (networkCallback != null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var lastWasWifi: Boolean? = null

                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    val last = lastWasWifi
                    if (last != null && wifi != last) {
                        lastNetworkSwitchAt = System.currentTimeMillis()
                        Log.i(TAG, "network transport switch detected")
                    }
                    lastWasWifi = wifi
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            connectivityManager = cm
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network watch failed", e)
        }
    }

    fun stopNetworkWatch() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
        connectivityManager = null
    }
}
