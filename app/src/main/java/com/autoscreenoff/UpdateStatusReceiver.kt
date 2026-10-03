package com.autoscreenoff

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * 更新安装会话的状态回调（[UpdateManager.installApk] 提交后由此接收结果）。
 *
 * STATUS_PENDING_USER_ACTION：系统要求用户确认 —— 回调 Intent 里带有系统组装好的
 * 确认界面 Intent（目标组件由系统指定，绕开本应用的包可见性/意图解析限制），
 * 直接 startActivity 拉起确认页；用户点「更新」后由系统完成安装。
 */
class UpdateStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = try {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                } catch (e: Exception) {
                    null
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                        Log.i("AutoScreenOff", "install confirmation shown")
                    } catch (e: Exception) {
                        Log.w("AutoScreenOff", "show install confirmation failed", e)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i("AutoScreenOff", "update installed successfully")
            else ->
                Log.w(
                    "AutoScreenOff",
                    "update install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                )
        }
    }
}
