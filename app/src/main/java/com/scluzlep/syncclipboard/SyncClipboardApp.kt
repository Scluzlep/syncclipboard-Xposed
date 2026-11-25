package com.scluzlep.syncclipboard

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.scluzlep.syncclipboard.config.ConfigStore
import com.scluzlep.syncclipboard.engine.RecentHashDedup
import com.scluzlep.syncclipboard.engine.SyncEngine
import com.scluzlep.syncclipboard.history.HistoryStore
import com.scluzlep.syncclipboard.ipc.ISyncEngineBridge
import com.scluzlep.syncclipboard.model.SyncEngineController
import com.scluzlep.syncclipboard.selector.NetworkMonitor
import com.scluzlep.syncclipboard.selector.NodeProber
import com.scluzlep.syncclipboard.selector.NodeSelector
import com.scluzlep.syncclipboard.service.XposedServiceBridge
import com.scluzlep.syncclipboard.tile.SyncToggleTileService

/**
 * Single-process application host wiring ConfigStore, HistoryStore, NetworkMonitor,
 * NodeSelector, SyncEngine, and LSPosed API 102 XposedServiceBridge.
 */
class SyncClipboardApp : Application() {

    lateinit var configStore: ConfigStore
        private set

    lateinit var historyStore: HistoryStore
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    lateinit var syncEngine: SyncEngine
        private set

    val engine: SyncEngineController
        get() = syncEngine

    val engineBridge: ISyncEngineBridge.Stub
        get() = syncEngine.engineBridge

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return

        configStore = ConfigStore(this)
        historyStore = HistoryStore(this)
        networkMonitor = NetworkMonitor(this)

        syncEngine = SyncEngine(
            context = this,
            configStore = configStore,
            historyStore = historyStore,
            networkMonitor = networkMonitor,
            nodeProber = NodeProber(),
            nodeSelector = NodeSelector(),
            recentHashDedup = RecentHashDedup(),
            onHideLauncherIconChanged = { hidden ->
                setLauncherIconHidden(this, hidden)
            }
        )

        setLauncherIconHidden(this, configStore.current.hideLauncherIcon)
        XposedServiceBridge.init(this)
        syncEngine.start()
        SyncToggleTileService.requestTileUpdate(this)
    }

    private fun isMainProcess(): Boolean {
        return runCatching { getProcessName() == packageName }.getOrDefault(true)
    }

    companion object {
        private const val LAUNCHER_ALIAS_CLASS = "com.scluzlep.syncclipboard.ui.LauncherAlias"

        /**
         * Toggles the launcher icon via [LauncherAlias] without disabling `MainActivity`,
         * so LSPosed Manager and Quick Settings tile preferences always remain accessible.
         */
        fun setLauncherIconHidden(context: Context, hidden: Boolean) {
            runCatching {
                val appContext = context.applicationContext
                val component = ComponentName(appContext, LAUNCHER_ALIAS_CLASS)
                val targetState = if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
                val pm = appContext.packageManager
                if (pm.getComponentEnabledSetting(component) != targetState) {
                    pm.setComponentEnabledSetting(
                        component,
                        targetState,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
    }
}
