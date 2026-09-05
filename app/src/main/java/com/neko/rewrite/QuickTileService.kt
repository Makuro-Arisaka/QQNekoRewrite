package com.neko.rewrite

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neko.rewrite.model.ModuleConfig

/**
 * Quick Settings 磁贴：一键启停猫娘改写。
 *
 * ## 为什么用 Tile 而不是「通知栏常驻开关」
 *
 * 之前的实现是在 QQ 进程里发一条常驻通知做开关。但 QQ 是多进程应用，
 * 每个进程都会跑 `Application.onCreate` 并各自判断「是否要显示/取消」那条通知；
 * 没收到过广播、配置回落默认的进程会把其它进程发布的通知误删，
 * 表现为「进入 QQ 后开关就没了」，且由于多进程竞态难以根治。
 *
 * Quick Settings Tile 由 SystemUI 托管，生命周期与 QQ 进程完全解耦，
 * 不会被 QQ 清掉，是最稳的一键开关方案。磁贴直接读写模块自身 SP 里的
 * `enabled` 总开关，并与设置页共享同一份配置（设置页保存即广播给 QQ，
 * 磁贴点按同理），二者始终一致。
 *
 * 在 Android 13+ 上，磁贴需用户手动从下拉菜单的「编辑」页添加
 * （系统不再允许应用自动添加），添加后在通知栏下拉即可常驻看到。
 */
class QuickTileService : TileService() {

    companion object {
        /**
         * 主动请求磁贴刷新（例如设置页修改了 enabled 后调用），
         * 让磁贴尽快同步到最新状态，不必等用户再次下拉。
         */
        fun refresh(context: android.content.Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    TileService.requestListeningState(
                        context,
                        ComponentName(context, QuickTileService::class.java)
                    )
                } catch (t: Throwable) {
                    LogRecorder.warn("QuickTile", "请求磁贴刷新失败: ${t.message}")
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // 每次下拉都重新读一次配置，保证与设置页 / 其它进程的改动同步
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            val ctx = this
            val current = ConfigManager.readLocalConfig(ctx)
            val newEnabled = !current.enabled

            // 复用与设置页完全相同的保存路径：写模块 SP + JSON、广播完整配置给 QQ，
            // 这样无论配置改自设置页还是磁贴，QQ 与设置页都不会出现两副状态。
            val updated = current.copy(
                enabled = newEnabled,
                lastUpdated = System.currentTimeMillis()
            )
            val ts = ConfigManager.saveFromSettings(ctx, updated)
            ConfigBroadcast.send(ctx, updated, ts)

            LogRecorder.success(
                "QuickTile",
                "磁贴切换：改写已${if (newEnabled) "启用" else "停用"}"
            )
            updateTile()
        } catch (t: Throwable) {
            LogRecorder.error(
                "QuickTile",
                "磁贴切换失败: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    /** 按当前 enabled 状态刷新磁贴外观（激活=绿 / 未激活=灰，并标注文字） */
    private fun updateTile() {
        val tile = qsTile ?: return
        try {
            val enabled = ConfigManager.readLocalConfig(this).enabled
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "猫娘改写"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (enabled) "已启用" else "已停用"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_neko)
            tile.updateTile()
        } catch (t: Throwable) {
            LogRecorder.warn("QuickTile", "磁贴刷新失败: ${t.message}")
        }
    }
}
