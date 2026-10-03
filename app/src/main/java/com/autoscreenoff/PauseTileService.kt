package com.autoscreenoff

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置（下拉通知栏）磁贴：一键开关总开关，无需打开 App。
 *
 * - 单击：切换总开关（同时联动启动/停止前台服务）
 * - 长按：进入应用设置页
 * - 磁贴状态每 60s 或由系统回调时刷新
 */
class PauseTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onClick() {
        val store = SettingsStore(this)
        val newState = !store.enabled
        store.enabled = newState
        if (newState) {
            MonitorForegroundService.ensureRunning(this)
        } else {
            MonitorForegroundService.stopIfDisabled(this)
        }
        updateTile()
    }

    private fun updateTile() {
        val store = SettingsStore(this)
        val snoozed = System.currentTimeMillis() < store.snoozeUntilMs
        val tile = qsTile ?: return
        tile.state = if (store.enabled && !snoozed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = when {
            !store.enabled -> "自动息屏：关"
            snoozed -> "自动息屏：已暂停"
            else -> "自动息屏：开"
        }
        tile.updateTile()
    }
}
