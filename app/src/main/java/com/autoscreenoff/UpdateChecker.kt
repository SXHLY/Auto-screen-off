package com.autoscreenoff

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查（仓库监听）。
 *
 * 仓库地址支持三种写法（设置页可配）：
 * - `github.com/owner/repo` → GitHub Releases API
 * - `gitee.com/owner/repo`  → Gitee Releases API（国内访问更稳定）
 * - 完整 API 地址（http/https 开头）→ 按原样请求（自建服务 / 内网调试场景）
 *
 * 解析 Release 的 tag_name（如 v1.0.12）与 assets 中第一个 .apk 的
 * browser_download_url，与本机 versionCode 比较 —— 本项目 versionCode ==
 * 构建号（见 app/build.gradle.kts 的自动递增），天然单调递增可直接比较。
 *
 * 这是本应用唯一的联网功能：仅请求用户配置的仓库地址，不上传任何数据。
 */
object UpdateChecker {

    private const val TAG = "AutoScreenOff"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    data class UpdateInfo(val versionName: String, val build: Long, val apkUrl: String)

    /** 当前应用构建号（versionCode） */
    fun currentBuild(context: Context): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pi)
    } catch (e: Exception) {
        0L
    }

    /** 仓库地址 → Releases API 地址；无法识别的格式返回 null */
    fun normalizeApiUrl(input: String): String? {
        val t = input.trim().trimEnd('/')
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        return when {
            lower.startsWith("https://") || lower.startsWith("http://") -> t
            lower.startsWith("github.com/") ->
                "https://api.github.com/repos/${t.substring("github.com/".length)}/releases/latest"
            lower.startsWith("gitee.com/") ->
                "https://gitee.com/api/v5/repos/${t.substring("gitee.com/".length)}/releases/latest"
            else -> null
        }
    }

    /** 拉取仓库最新 Release；失败（地址无效/网络/格式不符）返回 null */
    fun fetchLatest(context: Context): UpdateInfo? {
        val apiUrl = normalizeApiUrl(SettingsStore(context).updateRepoUrl) ?: run {
            Log.w(TAG, "update repo url invalid or empty")
            return null
        }
        return try {
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            // GitHub API 拒绝无 User-Agent 的请求
            conn.setRequestProperty("User-Agent", "AutoScreenOff-Updater")
            if (conn.responseCode != 200) {
                Log.w(TAG, "update check http ${conn.responseCode}")
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseRelease(body)
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            null
        }
    }

    /** 解析 GitHub / Gitee 通用格式的 Release JSON（tag_name + assets[].browser_download_url） */
    private fun parseRelease(body: String): UpdateInfo? {
        return try {
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name", "")
            val build = buildOf(tag) ?: return null
            val assets = obj.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val u = a.optString("browser_download_url", "")
                if (u.endsWith(".apk", true)) {
                    apkUrl = u
                    break
                }
            }
            if (apkUrl == null) null else UpdateInfo(tag.removePrefix("v").removePrefix("V"), build, apkUrl)
        } catch (e: Exception) {
            Log.w(TAG, "parse release failed", e)
            null
        }
    }

    /** 从 v1.0.12 / 1.0.12 提取构建号（末段数字）；不匹配返回 null */
    fun buildOf(version: String): Long? {
        val m = Regex("(\\d+)$").find(version.trim().removePrefix("v").removePrefix("V")) ?: return null
        return m.groupValues[1].toLongOrNull()
    }
}
