package com.scluzlep.syncclipboard.xposed

import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 module entry point for `com.scluzlep.syncclipboard`.
 *
 * Scopes:
 * - `system` (`system_server`, UID 1000): `SystemKeepAliveHooker` + `SystemNotificationHooker`
 * - `com.android.systemui` (main process only): `ClipboardHooker`
 */
class SyncClipboardModule : XposedModule() {

    @Volatile
    private var isSystemServerProcess: Boolean = false

    @Volatile
    private var currentProcessName: String = ""

    @Volatile
    private var isSystemUiMainProcess: Boolean = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        isSystemServerProcess = param.isSystemServer
        currentProcessName = param.processName
        log(Log.INFO, TAG, "Loaded API $apiVersion in process=${param.processName} systemServer=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        isSystemServerProcess = true
        installSystemServerHooks(param.classLoader)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        val pkg = param.packageName

        if (isSystemServerProcess || pkg == PACKAGE_SYSTEM || pkg == PACKAGE_ANDROID) {
            isSystemServerProcess = true
            val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                param.defaultClassLoader
            } else {
                ClassLoader.getSystemClassLoader()
            }
            installSystemServerHooks(loader)
            return
        }

        if (pkg == PACKAGE_SYSTEM_UI && param.applicationInfo.processName == pkg) {
            isSystemUiMainProcess = true
            runCatching {
                ClipboardHooker.install(this, param)
            }.onFailure { err ->
                log(Log.WARN, TAG, "ClipboardHooker.install failed", err)
            }
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        if (isSystemServerProcess) {
            val state = hashMapOf<String, Any>()
            SystemKeepAliveHooker.bootClassLoader?.let { state[KEY_BOOT_LOADER] = it }
            SystemNotificationHooker.bootClassLoader?.let { state[KEY_NMS_LOADER] = it }
            param.setSavedInstanceState(state)
            SystemKeepAliveHooker.unhookForReload()
            SystemNotificationHooker.unhook()
            log(Log.INFO, TAG, "Hot reloading initiated in system_server")
            return true
        }

        if (isSystemUiMainProcess || currentProcessName == PACKAGE_SYSTEM_UI) {
            param.setSavedInstanceState(PACKAGE_SYSTEM_UI)
            ClipboardHooker.unhookForReload()
            log(Log.INFO, TAG, "Hot reloading initiated in SystemUI")
            return true
        }

        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        super.onHotReloaded(param)
        currentProcessName = param.processName

        if (param.isSystemServer) {
            isSystemServerProcess = true
            val state = param.savedInstanceState as? Map<*, *>
            val keepAliveLoader = state?.get(KEY_BOOT_LOADER) as? ClassLoader
                ?: ClassLoader.getSystemClassLoader()
            val nmsLoader = state?.get(KEY_NMS_LOADER) as? ClassLoader
                ?: keepAliveLoader
            SystemKeepAliveHooker.install(this, keepAliveLoader)
            SystemNotificationHooker.install(this, nmsLoader)
            log(Log.INFO, TAG, "Hot reload completed in system_server")
            return
        }

        val savedTarget = param.savedInstanceState as? String
        if (savedTarget == PACKAGE_SYSTEM_UI || param.processName == PACKAGE_SYSTEM_UI) {
            isSystemUiMainProcess = true
            ClipboardHooker.onHotReloaded(this)
            log(Log.INFO, TAG, "Hot reload completed in SystemUI")
        }
    }

    private fun installSystemServerHooks(classLoader: ClassLoader?) {
        runCatching {
            SystemKeepAliveHooker.install(this, classLoader)
        }.onFailure { err ->
            log(Log.WARN, TAG, "SystemKeepAliveHooker.install failed", err)
        }
        runCatching {
            SystemNotificationHooker.install(this, classLoader)
        }.onFailure { err ->
            log(Log.WARN, TAG, "SystemNotificationHooker.install failed", err)
        }
    }

    companion object {
        private const val TAG = "SyncClipboardModule"
        private const val PACKAGE_SYSTEM_UI = "com.android.systemui"
        private const val PACKAGE_SYSTEM = "system"
        private const val PACKAGE_ANDROID = "android"
        private const val KEY_BOOT_LOADER = "bootLoader"
        private const val KEY_NMS_LOADER = "nmsLoader"
    }
}
