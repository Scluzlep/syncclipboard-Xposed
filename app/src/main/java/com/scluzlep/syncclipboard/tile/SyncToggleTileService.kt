package com.scluzlep.syncclipboard.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.scluzlep.syncclipboard.R
import com.scluzlep.syncclipboard.SyncClipboardApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings Tile for toggling background auto-sync (`autoSyncEnabled`).
 */
class SyncToggleTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val engine = (applicationContext as? SyncClipboardApp)?.engine ?: return
        syncTileState(
            autoSyncEnabled = engine.configFlow.value.autoSyncEnabled,
            activeNodeName = engine.statusFlow.value.activeNodeName
        )

        observerJob?.cancel()
        observerJob = serviceScope.launch {
            combine(engine.configFlow, engine.statusFlow) { cfg, status ->
                cfg.autoSyncEnabled to status.activeNodeName
            }.collect { (enabled, nodeName) ->
                syncTileState(enabled, nodeName)
            }
        }
    }

    override fun onStopListening() {
        observerJob?.cancel()
        observerJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val engine = (applicationContext as? SyncClipboardApp)?.engine ?: return
        var nextEnabled = true
        engine.updateConfig { current ->
            nextEnabled = !current.autoSyncEnabled
            current.copy(autoSyncEnabled = nextEnabled)
        }
        syncTileState(nextEnabled, engine.statusFlow.value.activeNodeName)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        observerJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun syncTileState(autoSyncEnabled: Boolean, activeNodeName: String?) {
        val tile = qsTile ?: return
        tile.state = if (autoSyncEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_sync_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (autoSyncEnabled) {
                activeNodeName?.takeIf { it.isNotBlank() } ?: getString(R.string.tile_sync_on)
            } else {
                getString(R.string.tile_sync_off)
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, SyncToggleTileService::class.java)
                )
            }
        }
    }
}
