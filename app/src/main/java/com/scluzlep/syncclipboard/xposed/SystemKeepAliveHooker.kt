package com.scluzlep.syncclipboard.xposed

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.scluzlep.syncclipboard.ipc.ISyncEngineBridge
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `system_server` (UID 1000) zero-touch keepalive & recovery hooker.
 *
 * Responsibilities:
 * 1. Tier 1 (`IDeviceIdleController.addPowerSaveWhitelistApp`) + Tier 2 (`DeviceIdleInternal`)
 *    automatic Doze & kernel eBPF `sUidOwnerMap` whitelisting.
 * 2. Privileged binding to `SyncClipboardEngineService` with
 *    `BIND_AUTO_CREATE | BIND_IMPORTANT | BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES` (`0x04001041`).
 * 3. Scoped `forceStopPackage` / OEM-cleaner guard preserving uninstall, update, and explicit Settings force-stop.
 * 4. `<500ms` self-healing `onBindingDied` recovery calling `IPackageManager.setPackageStoppedState(..., false, userId)`
 *    before rebinding.
 */
object SystemKeepAliveHooker {

    private const val TAG = "SyncKeepAliveHook"
    private const val TARGET_PKG = "com.scluzlep.syncclipboard"
    private const val TARGET_SERVICE = "com.scluzlep.syncclipboard.service.SyncClipboardEngineService"
    private const val AMS_CLASS = "com.android.server.am.ActivityManagerService"
    private const val DIC_CLASS = "com.android.server.DeviceIdleController"
    private const val DEVICE_IDLE_INTERNAL_CLASS = "com.android.server.DeviceIdleInternal"

    // BIND_AUTO_CREATE (0x0001) | BIND_IMPORTANT (0x0040) | BIND_INCLUDE_CAPABILITIES (0x1000) | BIND_FOREGROUND_SERVICE (0x04000000)
    private const val PRIVILEGED_BIND_FLAGS = 0x04001041

    private const val FAST_REBIND_DELAY_MS = 200L
    private const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
    private const val CRASH_LOOP_WINDOW_MS = 10_000L
    private const val CRASH_LOOP_THRESHOLD = 5
    private const val CRASH_LOOP_BACKOFF_MS = 10_000L

    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val localHeartbeatToken = Binder()
    private val handlerThread = HandlerThread("scx-keepalive").apply { start() }
    private val handler = Handler(handlerThread.looper)

    @Volatile
    var bootClassLoader: ClassLoader? = null
        private set

    @Volatile
    private var moduleRef: XposedInterface? = null

    @Volatile
    private var installed = false

    @Volatile
    private var receiversRegistered = false

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var engineBridge: ISyncEngineBridge? = null

    @Volatile
    private var isBound = false

    @Volatile
    private var dozeWhitelisted = false

    private var deathWindowStartMs = 0L
    private var deathsInWindow = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bridge = ISyncEngineBridge.Stub.asInterface(service)
            engineBridge = bridge
            isBound = bridge != null
            if (bridge != null) {
                handler.post {
                    runCatching {
                        bridge.registerSystemServerHeartbeat(localHeartbeatToken, dozeWhitelisted)
                    }.onFailure { err ->
                        logWarn("registerSystemServerHeartbeat failed", err)
                    }
                }
            }
            logInfo("Bound to SyncClipboardEngineService (dozeWhitelisted=$dozeWhitelisted)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBridge = null
            isBound = false
            scheduleSelfHeal("onServiceDisconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            engineBridge = null
            isBound = false
            scheduleSelfHeal("onBindingDied")
        }

        override fun onNullBinding(name: ComponentName?) {
            engineBridge = null
            isBound = false
            scheduleSelfHeal("onNullBinding")
        }
    }

    private val systemEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_USER_UNLOCKED,
                ACTION_USER_SWITCHED -> {
                    handler.post { ensureWhitelistedAndBound() }
                }

                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val pkg = intent.data?.schemeSpecificPart
                    if (pkg == TARGET_PKG) {
                        handler.postDelayed({ ensureWhitelistedAndBound(forceRebind = true) }, 250L)
                    }
                }
            }
        }
    }

    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader?) {
        moduleRef = module
        if (classLoader != null) bootClassLoader = classLoader
        if (installed) return

        val loader = resolveServerLoader(classLoader)
        bootClassLoader = loader

        hookAmsSystemReady(module, loader)
        hookForceStopGuards(module, loader)
        hookDeviceIdleRemovalGuard(module, loader)

        installed = true

        // If system_server is already up (e.g. hot reload), bootstrap immediately.
        val ctx = resolveSystemContext()
        if (ctx != null) {
            registerSystemReceivers(ctx)
            handler.post { ensureWhitelistedAndBound() }
        }
        logInfo("SystemKeepAliveHooker installed in system_server")
    }

    @Synchronized
    fun unhookForReload() {
        hookHandles.forEach { runCatching { it.unhook() } }
        hookHandles.clear()
        handler.removeCallbacksAndMessages(null)
        val ctx = systemContext
        if (ctx != null) {
            if (receiversRegistered) {
                runCatching { ctx.unregisterReceiver(systemEventsReceiver) }
                receiversRegistered = false
            }
            if (isBound) {
                runCatching { ctx.unbindService(serviceConnection) }
            }
        }
        isBound = false
        engineBridge = null
        installed = false
    }

    fun getBridge(): ISyncEngineBridge? = engineBridge

    fun ensureBoundAsync() {
        handler.post { ensureWhitelistedAndBound() }
    }

    private fun hookAmsSystemReady(module: XposedInterface, loader: ClassLoader) {
        runCatching {
            val amsClass = Class.forName(AMS_CLASS, false, loader)
            amsClass.declaredMethods
                .filter { it.name == "systemReady" }
                .forEach { method ->
                    val handle = module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching {
                                val amsObj = chain.thisObject
                                if (systemContext == null && amsObj != null) {
                                    val ctxField = runCatching {
                                        amsClass.getDeclaredField("mContext").apply { isAccessible = true }
                                    }.getOrNull()
                                    val ctx = ctxField?.get(amsObj) as? Context
                                    if (ctx != null) systemContext = ctx
                                }
                                resolveSystemContext()?.let { ctx ->
                                    registerSystemReceivers(ctx)
                                    handler.postDelayed({ ensureWhitelistedAndBound() }, 500L)
                                }
                            }
                            result
                        }
                    hookHandles.add(handle)
                }
        }.onFailure { err ->
            logWarn("Failed hooking AMS.systemReady", err)
        }
    }

    private fun hookForceStopGuards(module: XposedInterface, loader: ClassLoader) {
        runCatching {
            val amsClass = Class.forName(AMS_CLASS, false, loader)
            amsClass.declaredMethods
                .filter {
                    (it.name == "forceStopPackage" || it.name == "forceStopPackageWithMode") &&
                        it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0] == String::class.java
                }
                .forEach { method ->
                    val handle = module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val pkg = chain.args.firstOrNull() as? String
                            if (pkg == TARGET_PKG && shouldProtectFromForceStop(chain.thisObject)) {
                                logInfo("Protected $TARGET_PKG from non-user background forceStopPackage")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                    hookHandles.add(handle)
                }
        }.onFailure { err ->
            logWarn("Failed hooking AMS.forceStopPackage guard", err)
        }

        // Optional MIUI/HyperOS ProcessManagerInternal forceStopPackage hook
        runCatching {
            val pmiClass = Class.forName("com.miui.server.process.ProcessManagerInternal", false, loader)
            pmiClass.declaredMethods
                .filter {
                    it.name.contains("forceStop", ignoreCase = true) &&
                        it.parameterTypes.any { p -> p == String::class.java }
                }
                .forEach { method ->
                    val handle = module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val pkg = chain.args.firstOrNull { it is String } as? String
                            if (pkg == TARGET_PKG && shouldProtectFromForceStop(null)) {
                                logInfo("Protected $TARGET_PKG from MIUI ProcessManager forceStop")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                    hookHandles.add(handle)
                }
        }
    }

    private fun hookDeviceIdleRemovalGuard(module: XposedInterface, loader: ClassLoader) {
        runCatching {
            val dicClass = Class.forName(DIC_CLASS, false, loader)
            dicClass.declaredMethods
                .filter {
                    it.name == "removePowerSaveWhitelistAppInternal" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                .forEach { method ->
                    val handle = module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept { chain ->
                            val pkg = chain.args.firstOrNull() as? String
                            if (pkg == TARGET_PKG && !isUninstallOrUserActionStack()) {
                                logInfo("Protected $TARGET_PKG from automatic Doze whitelist removal")
                                return@intercept true
                            }
                            chain.proceed()
                        }
                    hookHandles.add(handle)
                }
        }.onFailure { err ->
            logWarn("Failed hooking DeviceIdleController removal guard", err)
        }
    }

    private fun shouldProtectFromForceStop(amsInstance: Any?): Boolean {
        if (isUninstallOrUserActionStack()) return false
        val callingUid = Binder.getCallingUid()
        if (callingUid != 1000 && callingUid != 0) {
            val ctx = resolveSystemContext()
            val callerPkgs = ctx?.let {
                runCatching { it.packageManager.getPackagesForUid(callingUid) }.getOrNull()
            }
            // Allow Android Settings explicit "Force stop" button
            if (callerPkgs?.any { it == "com.android.settings" } == true) {
                return false
            }
        }
        // Also check stack trace for Settings / PackageInstaller / PackageManagerService lifecycle ops
        if (amsInstance != null && isExplicitSettingsCaller(amsInstance)) {
            return false
        }
        return true
    }

    private fun isExplicitSettingsCaller(@Suppress("UNUSED_PARAMETER") amsInstance: Any): Boolean {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val cls = frame.className
            if (cls.contains("PackageInstaller") || cls.contains("AppInfoDashboardFragment")) {
                return true
            }
        }
        return false
    }

    private fun isUninstallOrUserActionStack(): Boolean {
        val stack = Thread.currentThread().stackTrace
        for (frame in stack) {
            val method = frame.methodName
            val cls = frame.className
            if (method == "deletePackage" ||
                method == "deletePackageX" ||
                method == "deletePackageVersioned" ||
                method == "clearApplicationUserData" ||
                method == "setApplicationEnabledSetting" ||
                method == "installPackage" ||
                method == "installPackageLI" ||
                method == "replacePackage" ||
                method == "onPackageRemoved" ||
                method == "onPackageFullyRemoved" ||
                cls.contains("PackageInstallerService")
            ) {
                return true
            }
        }
        return false
    }

    @Synchronized
    private fun registerSystemReceivers(ctx: Context) {
        if (receiversRegistered) return
        runCatching {
            val sysFilter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(ACTION_USER_SWITCHED)
            }
            ctx.registerReceiver(systemEventsReceiver, sysFilter)

            val pkgFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ctx.registerReceiver(systemEventsReceiver, pkgFilter)
            receiversRegistered = true
        }.onFailure { err ->
            logWarn("Failed registering system receivers", err)
        }
    }

    private fun ensureWhitelistedAndBound(forceRebind: Boolean = false) {
        val ctx = resolveSystemContext() ?: return
        val userId = currentUserId()
        dozeWhitelisted = ensureDozeWhitelisted(userId)

        if (!isUserUnlocked(ctx, userId)) {
            logInfo("User $userId still CE-locked; deferring EngineService bind until ACTION_USER_UNLOCKED")
            return
        }

        if (isBound && engineBridge != null && !forceRebind) {
            runCatching {
                engineBridge?.registerSystemServerHeartbeat(localHeartbeatToken, dozeWhitelisted)
            }
            return
        }

        bindEngineService(ctx, userId, forceRebind)
    }

    private fun ensureDozeWhitelisted(userId: Int): Boolean {
        val identity = Binder.clearCallingIdentity()
        try {
            // Tier 1: IDeviceIdleController Binder service -> permanent whitelist + eBPF HAPPY_BOX_MATCH
            val tier1 = runCatching {
                val smClass = Class.forName("android.os.ServiceManager")
                val binder = smClass.getMethod("getService", String::class.java).invoke(null, "deviceidle") as? IBinder
                    ?: return@runCatching false
                val stubClass = Class.forName("android.os.IDeviceIdleController\$Stub")
                val controller = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    ?: return@runCatching false

                val isWhitelisted = runCatching {
                    controller.javaClass.getMethod("isPowerSaveWhitelistApp", String::class.java)
                        .invoke(controller, TARGET_PKG) as? Boolean
                }.getOrNull()

                if (isWhitelisted == true) {
                    return@runCatching true
                }

                controller.javaClass.getMethod("addPowerSaveWhitelistApp", String::class.java)
                    .invoke(controller, TARGET_PKG)
                true
            }.onFailure { err ->
                logWarn("Tier 1 IDeviceIdleController whitelist failed", err)
            }.getOrDefault(false)

            if (tier1) return true

            // Tier 2: DeviceIdleInternal via LocalServices (5/6/7-arg reflection)
            val loader = bootClassLoader ?: ClassLoader.getSystemClassLoader()
            return runCatching {
                val localServicesClass = Class.forName("com.android.server.LocalServices", false, loader)
                val internalInterface = Class.forName(DEVICE_IDLE_INTERNAL_CLASS, false, loader)
                val internalService = localServicesClass.getMethod("getService", Class::class.java)
                    .invoke(null, internalInterface) ?: return@runCatching false

                for (method in internalService.javaClass.methods) {
                    if (method.name != "addPowerSaveTempWhitelistApp") continue
                    val params = method.parameterTypes
                    val invoked = when (params.size) {
                        7 -> runCatching {
                            method.invoke(internalService, 1000, TARGET_PKG, 300_000L, userId, true, 0, "syncclipboard")
                            true
                        }.getOrDefault(false)

                        6 -> runCatching {
                            method.invoke(internalService, 1000, TARGET_PKG, 300_000L, userId, true, "syncclipboard")
                            true
                        }.getOrDefault(false)

                        5 -> runCatching {
                            method.invoke(internalService, 1000, TARGET_PKG, 300_000L, userId, "syncclipboard")
                            true
                        }.getOrDefault(false)

                        else -> false
                    }
                    if (invoked) return@runCatching true
                }
                false
            }.onFailure { err ->
                logWarn("Tier 2 DeviceIdleInternal whitelist failed", err)
            }.getOrDefault(false)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun clearPackageStoppedState(userId: Int) {
        val identity = Binder.clearCallingIdentity()
        try {
            runCatching {
                val smClass = Class.forName("android.os.ServiceManager")
                val binder = smClass.getMethod("getService", String::class.java).invoke(null, "package") as? IBinder
                    ?: return@runCatching
                val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
                val ipm = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    ?: return@runCatching
                ipm.javaClass.getMethod(
                    "setPackageStoppedState",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(ipm, TARGET_PKG, false, userId)
            }.onFailure { err ->
                logWarn("setPackageStoppedState(false) failed", err)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun bindEngineService(ctx: Context, userId: Int, unbindFirst: Boolean) {
        val identity = Binder.clearCallingIdentity()
        try {
            if (unbindFirst) {
                runCatching { ctx.unbindService(serviceConnection) }
                isBound = false
                engineBridge = null
            }

            clearPackageStoppedState(userId)

            val intent = Intent().apply {
                component = ComponentName(TARGET_PKG, TARGET_SERVICE)
            }

            val currentUserHandle = runCatching {
                UserHandle::class.java.getField("CURRENT").get(null) as UserHandle
            }.getOrNull()

            val bound = if (currentUserHandle != null) {
                runCatching {
                    Context::class.java.getMethod(
                        "bindServiceAsUser",
                        Intent::class.java,
                        ServiceConnection::class.java,
                        Int::class.javaPrimitiveType,
                        UserHandle::class.java
                    ).invoke(ctx, intent, serviceConnection, PRIVILEGED_BIND_FLAGS, currentUserHandle) as? Boolean
                }.getOrNull()
            } else {
                null
            } ?: runCatching {
                ctx.bindService(intent, serviceConnection, PRIVILEGED_BIND_FLAGS)
            }.getOrDefault(false)

            if (!bound) {
                logWarn("bindServiceAsUser returned false; scheduling self-heal")
                scheduleSelfHeal("bindReturnedFalse")
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun scheduleSelfHeal(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - deathWindowStartMs > CRASH_LOOP_WINDOW_MS) {
            deathWindowStartMs = now
            deathsInWindow = 1
        } else {
            deathsInWindow++
        }
        val delayMs = if (deathsInWindow >= CRASH_LOOP_THRESHOLD) CRASH_LOOP_BACKOFF_MS else FAST_REBIND_DELAY_MS
        logWarn("Scheduling self-heal ($reason) in ${delayMs}ms (deathsInWindow=$deathsInWindow)")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val ctx = resolveSystemContext() ?: return@postDelayed
            val userId = currentUserId()
            dozeWhitelisted = ensureDozeWhitelisted(userId)
            if (isUserUnlocked(ctx, userId)) {
                bindEngineService(ctx, userId, unbindFirst = true)
            }
        }, delayMs)
    }

    private fun currentUserId(): Int = runCatching {
        val amClass = Class.forName("android.app.ActivityManager")
        amClass.getMethod("getCurrentUser").invoke(null) as? Int ?: 0
    }.getOrDefault(0)

    private fun isUserUnlocked(ctx: Context, userId: Int): Boolean {
        val um = ctx.getSystemService(UserManager::class.java) ?: return true
        return runCatching {
            um.javaClass.getMethod("isUserUnlocked", Int::class.javaPrimitiveType).invoke(um, userId) as? Boolean
        }.getOrNull() ?: runCatching { um.isUserUnlocked }.getOrDefault(true)
    }

    private fun resolveSystemContext(): Context? {
        systemContext?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return@runCatching null
            val method = atClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val ctx = method.invoke(at) as? Context
            if (ctx != null) systemContext = ctx
            ctx
        }.getOrNull()
    }

    private fun resolveServerLoader(provided: ClassLoader?): ClassLoader {
        val candidates = listOfNotNull(
            provided,
            runCatching { ClassLoader.getSystemClassLoader() }.getOrNull(),
            SystemKeepAliveHooker::class.java.classLoader
        )
        for (candidate in candidates) {
            if (runCatching { Class.forName(AMS_CLASS, false, candidate) }.isSuccess) {
                return candidate
            }
        }
        return provided ?: ClassLoader.getSystemClassLoader()
    }

    private fun logInfo(msg: String) {
        moduleRef?.log(Log.INFO, TAG, msg) ?: Log.i(TAG, msg)
    }

    private fun logWarn(msg: String, err: Throwable? = null) {
        if (err != null) {
            moduleRef?.log(Log.WARN, TAG, msg, err) ?: Log.w(TAG, msg, err)
        } else {
            moduleRef?.log(Log.WARN, TAG, msg) ?: Log.w(TAG, msg)
        }
    }
}
