package com.autoscreenoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启：总开关开启时启动前台服务。
 *
 * 说明：
 * - 无障碍服务的启用状态在重启后由系统保留（无需重新授权），但服务进程
 *   需要随开机重新拉起，本接收器负责把保活链路重启；
 * - RECEIVE_BOOT_COMPLETED 为普通级权限，安装即授予；
 * - 国产 ROM（MIUI/EMUI/ColorOS 等）通常还要求在系统设置中手动允许"自启动"，
 *   见 README「后台保活」章节。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("AutoScreenOff", "boot completed")
        if (SettingsStore(context).enabled) {
            MonitorForegroundService.ensureRunning(context)
        }
    }
}
