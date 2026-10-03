package com.autoscreenoff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新流程编排：发现新版本通知 → 应用内流式下载（进度通知）→ 下载完成通知
 * （点击调起系统安装器，FileProvider 授权读取 APK）。
 *
 * 下载到应用私有外部目录（无需存储权限），断点/失败自动清理残留文件。
 */
object UpdateManager {

    private const val TAG = "AutoScreenOff"

    const val CHANNEL_ID = "update"
    const val NOTIF_ID = 4
    const val ACTION_DOWNLOAD = "com.autoscreenoff.action.UPDATE_DOWNLOAD"

    /** 通知 → MainActivity → 安装器的跳转参数（APK 绝对路径） */
    const val EXTRA_INSTALL_APK = "install_apk_path"

    private const val APK_MIME = "application/vnd.android-package-archive"

    @Volatile
    private var downloading = false

    fun createChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "版本更新", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** 发现新版本 → 高优先级通知（带「下载」动作按钮） */
    fun showUpdateNotification(context: Context, info: UpdateChecker.UpdateInfo) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(context)
            val dlPi = PendingIntent.getService(
                context, 5,
                Intent(context, MonitorForegroundService::class.java)
                    .setAction(ACTION_DOWNLOAD),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val openPi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                NOTIF_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_apps)
                    .setContentTitle("发现新版本 v${info.versionName}")
                    .setContentText("点击「下载」获取更新包")
                    .setContentIntent(openPi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .addAction(Notification.Action.Builder(null, "下载", dlPi).build())
                    .build()
            )
            Log.i(TAG, "update available: v${info.versionName}")
        } catch (e: Exception) {
            Log.w(TAG, "show update notification failed", e)
        }
    }

    /** 应用内下载 APK 到应用私有目录，完成后弹「点击安装」通知 */
    fun download(context: Context, url: String, versionName: String) {
        if (downloading) return
        downloading = true
        val appContext = context.applicationContext
        Log.i(TAG, "downloading update v$versionName from $url")
        Thread {
            val dir = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates"
            ).apply { mkdirs() }
            val file = File(dir, "自动息屏-v$versionName.apk")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode !in 200..299) throw IOException("http ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastProgress = -1
                var lastProgressPostAt = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                val now = System.currentTimeMillis()
                                // 进度通知按时间节流（≥800ms）：过快下载若按百分比发布
                                // 会触发系统包级通知限流（NotificationService "Shedding"），
                                // 把随后的「下载完成/安装」通知一起丢弃
                                if (p != lastProgress && now - lastProgressPostAt > 800) {
                                    lastProgress = p
                                    lastProgressPostAt = now
                                    notifyProgress(appContext, p)
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                Log.i(TAG, "update apk downloaded: ${file.name} ($done bytes)")
                showInstallNotification(appContext, file, versionName)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                file.delete()
                notifyFailed(appContext)
            } finally {
                downloading = false
            }
        }.start()
    }

    private fun notifyProgress(context: Context, progress: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_apps)
                    .setContentTitle("正在下载新版本")
                    .setProgress(100, progress, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    /** 下载完成 → 点击打开应用并直接调起系统安装器。
     *  不用通知 contentIntent 直接装 APK：Android 14+ 上「不可变 PendingIntent +
     *  URI 授权」发送会失败（SystemUI 回退为仅打开应用），改为通知带路径打开
     *  MainActivity，由 Activity 直接 startActivity 授权安装（标准路径）。 */
    private fun showInstallNotification(context: Context, file: File, versionName: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val open = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_INSTALL_APK, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // requestCode 必须是新值：不可变 PendingIntent 一经创建无法被 FLAG_UPDATE_CURRENT
            // 修改，复用旧 code 会命中系统里残留的旧记录（旧 intent），点击行为错误
            val pi = PendingIntent.getActivity(
                context, 7, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                NOTIF_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_check_circle)
                    .setContentTitle("新版本 v$versionName 已下载")
                    .setContentText("点击安装（更新检查为应用唯一联网功能）")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
            Log.i(TAG, "install notification posted for v$versionName")
        } catch (e: Exception) {
            Log.w(TAG, "show install notification failed", e)
        }
    }

    /** 由 Activity 调起安装：PackageInstaller 会话提交（正统自更新方式）。
     *  系统确认界面通过 STATUS_PENDING_USER_ACTION 回调里的 Intent 拉起——
     *  由系统指定目标组件，不依赖本应用的 intent 解析/包可见性（部分系统的
     *  安装器用新式过滤器，传统 VIEW+MIME 隐式解析会失败）。 */
    fun installApk(context: Context, apkPath: String) {
        val file = File(apkPath)
        if (!file.exists()) {
            Log.w(TAG, "install apk missing: $apkPath")
            return
        }
        try {
            val installer = context.packageManager.packageInstaller
            val session = installer.openSession(
                installer.createSession(
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                )
            )
            file.inputStream().use { input ->
                session.openWrite("update", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val statusPi = PendingIntent.getBroadcast(
                context, 8,
                Intent(context, UpdateStatusReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(statusPi.intentSender)
            Log.i(TAG, "install session committed for $apkPath")
        } catch (e: Exception) {
            Log.w(TAG, "install session failed", e)
        }
    }

    private fun notifyFailed(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_warning)
                    .setContentTitle("更新下载失败")
                    .setContentText("请检查网络后重试（设置页可再次检查更新）")
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }
}
