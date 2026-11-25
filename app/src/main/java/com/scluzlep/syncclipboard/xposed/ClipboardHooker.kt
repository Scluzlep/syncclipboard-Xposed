package com.scluzlep.syncclipboard.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.UserManager
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.scluzlep.syncclipboard.ipc.ISyncEngineBridge
import com.scluzlep.syncclipboard.ipc.ISystemUiClipboardClient
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `com.android.systemui` (main process only) clipboard I/O bridge & overlay suppressor.
 *
 * - DirectBoot guard: waits for `ACTION_USER_UNLOCKED` before binding `SyncClipboardEngineService`.
 * - Exponential backoff rebind + pending 1-item cache when disconnected.
 * - Reads cross-app `content://` URIs via SystemUI `ContentResolver` -> temporary `ParcelFileDescriptor`s.
 * - Writes text/URIs back with `com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY` + `EXTRA_IS_SENSITIVE`.
 * - Lightweight `ClipboardListener` hook suppressing overlay popup when `!powerManager.isInteractive`
 *   or when `SUPPRESS_CLIPBOARD_OVERLAY` is marked.
 */
object ClipboardHooker {

    private const val TAG = "SyncClipHook"
    private const val TARGET_PKG = "com.scluzlep.syncclipboard"
    private const val TARGET_SERVICE = "com.scluzlep.syncclipboard.service.SyncClipboardEngineService"
    private const val EXTRA_SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
    private const val EXTRA_IS_SENSITIVE_COMPAT = "android.content.extra.IS_SENSITIVE"
    private const val EXTRA_SYNC_ORIGIN = "com.scluzlep.syncclipboard.ORIGIN_REMOTE"
    private const val MAX_SINGLE_FILE_BYTES = 128L * 1024L * 1024L
    private const val ECHO_GUARD_WINDOW_MS = 1_500L
    private const val MAX_REBIND_DELAY_MS = 30_000L

    private sealed interface PendingClip {
        data class Text(val text: String, val isSensitive: Boolean, val timestampMs: Long) : PendingClip
        data class Uris(
            val uriStrings: List<String>,
            val fallbackText: String,
            val timestampMs: Long
        ) : PendingClip
    }

    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scx-systemui-clip").apply { isDaemon = true }
    }
    private val appAttached = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)
    private val unlockReceiverRegistered = AtomicBoolean(false)

    @Volatile
    private var moduleRef: XposedInterface? = null

    @Volatile
    private var systemUiApp: Application? = null

    @Volatile
    private var engineBridge: ISyncEngineBridge? = null

    @Volatile
    private var rebindAttempt = 0

    @Volatile
    private var lastRemoteWriteAtMs = 0L

    @Volatile
    private var pendingClip: PendingClip? = null

    private val primaryClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        readAndForwardPrimaryClip(ignoreSuppression = false)
    }

    private val clipboardClient = object : ISystemUiClipboardClient.Stub() {
        override fun writeTextToClipboard(text: String?, sensitive: Boolean, suppressOverlay: Boolean) {
            if (text == null) return
            val app = systemUiApp ?: return
            mainHandler.post {
                writeClipOnMain(app) {
                    val clip = ClipData.newPlainText("SyncClipboard", text)
                    applyExtras(clip.description, sensitive, suppressOverlay)
                    clip
                }
            }
        }

        override fun writeUriToClipboard(
            uriString: String?,
            mimeType: String?,
            label: String?,
            sensitive: Boolean,
            suppressOverlay: Boolean
        ) {
            if (uriString.isNullOrBlank()) return
            val app = systemUiApp ?: return
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
            val effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: "*/*"
            val effectiveLabel = label?.takeIf { it.isNotBlank() } ?: "SyncClipboard"
            mainHandler.post {
                writeClipOnMain(app) {
                    val clip = ClipData(effectiveLabel, arrayOf(effectiveMime), ClipData.Item(uri))
                    applyExtras(clip.description, sensitive, suppressOverlay)
                    clip
                }
            }
        }

        override fun requestCurrentClipboardPush() {
            readAndForwardPrimaryClip(ignoreSuppression = true)
        }

        override fun ping() {
            // Binder health check
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bridge = ISyncEngineBridge.Stub.asInterface(service)
            engineBridge = bridge
            rebindAttempt = 0
            if (bridge != null) {
                clipExecutor.execute {
                    runCatching {
                        bridge.registerClipboardClient(clipboardClient)
                    }.onFailure { err ->
                        logWarn("registerClipboardClient failed", err)
                    }
                    flushPendingClip(bridge)
                }
            }
            logInfo("SystemUI bound to SyncClipboardEngineService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBridge = null
            scheduleReconnect("onServiceDisconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            engineBridge = null
            scheduleReconnect("onBindingDied")
        }

        override fun onNullBinding(name: ComponentName?) {
            engineBridge = null
            scheduleReconnect("onNullBinding")
        }
    }

    private val userUnlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                systemUiApp?.let { app ->
                    mainHandler.post { onUserUnlockedReady(app) }
                }
            }
        }
    }

    fun install(module: XposedInterface, param: XposedModuleInterface.PackageLoadedParam) {
        moduleRef = module
        if (param.applicationInfo.processName != param.packageName) return

        val fallbackLoader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            param.defaultClassLoader
        } else {
            ClassLoader.getSystemClassLoader()
        }
        val existingApp = currentApplication()
        if (existingApp != null) {
            onApplicationReady(module, existingApp, existingApp.classLoader ?: fallbackLoader)
            return
        }

        val appClassName = param.applicationInfo.className ?: "android.app.Application"
        val onCreateMethod = runCatching {
            fallbackLoader.loadClass(appClassName).getDeclaredMethod("onCreate")
        }.getOrElse {
            runCatching { Application::class.java.getDeclaredMethod("onCreate") }.getOrNull()
        }

        if (onCreateMethod != null) {
            runCatching {
                val handle = module.hook(onCreateMethod)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept { chain ->
                        val res = chain.proceed()
                        val app = chain.thisObject as? Application
                        if (app != null) {
                            onApplicationReady(module, app, app.classLoader ?: fallbackLoader)
                        }
                        res
                    }
                hookHandles.add(handle)
            }.onFailure { err ->
                logWarn("Failed hooking SystemUI Application.onCreate", err)
            }
        }
    }

    fun onHotReloaded(module: XposedInterface) {
        moduleRef = module
        val app = currentApplication() ?: return
        onApplicationReady(module, app, app.classLoader ?: ClipboardHooker::class.java.classLoader!!)
    }

    fun unhookForReload() {
        hookHandles.forEach { runCatching { it.unhook() } }
        hookHandles.clear()
        mainHandler.removeCallbacksAndMessages(null)
        val app = systemUiApp
        if (app != null) {
            if (listenerRegistered.compareAndSet(true, false)) {
                runCatching {
                    app.getSystemService(ClipboardManager::class.java)
                        ?.removePrimaryClipChangedListener(primaryClipListener)
                }
            }
            if (unlockReceiverRegistered.compareAndSet(true, false)) {
                runCatching { app.unregisterReceiver(userUnlockedReceiver) }
            }
            runCatching { engineBridge?.unregisterClipboardClient(clipboardClient) }
            runCatching { app.unbindService(serviceConnection) }
        }
        engineBridge = null
        appAttached.set(false)
    }

    private fun onApplicationReady(module: XposedInterface, app: Application, classLoader: ClassLoader) {
        systemUiApp = app
        if (!appAttached.compareAndSet(false, true)) return

        installClipboardOverlaySuppressor(module, app, classLoader)

        if (isUserUnlocked(app)) {
            onUserUnlockedReady(app)
        } else if (unlockReceiverRegistered.compareAndSet(false, true)) {
            runCatching {
                app.registerReceiver(userUnlockedReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            }.onFailure {
                unlockReceiverRegistered.set(false)
            }
        }
    }

    private fun onUserUnlockedReady(app: Application) {
        if (unlockReceiverRegistered.compareAndSet(true, false)) {
            runCatching { app.unregisterReceiver(userUnlockedReceiver) }
        }
        registerPrimaryClipListener(app)
        bindEngineService(app)
    }

    private fun registerPrimaryClipListener(app: Application) {
        if (!listenerRegistered.compareAndSet(false, true)) return
        runCatching {
            val cm = app.getSystemService(ClipboardManager::class.java)
            cm?.addPrimaryClipChangedListener(primaryClipListener)
            logInfo("Registered SystemUI OnPrimaryClipChangedListener")
        }.onFailure { err ->
            listenerRegistered.set(false)
            logWarn("Failed registering OnPrimaryClipChangedListener", err)
        }
    }

    private fun bindEngineService(app: Application) {
        if (!isUserUnlocked(app)) return
        val intent = Intent().apply {
            component = ComponentName(TARGET_PKG, TARGET_SERVICE)
        }
        val ok = runCatching {
            app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        }.getOrDefault(false)
        if (!ok) {
            scheduleReconnect("bindReturnedFalse")
        }
    }

    private fun scheduleReconnect(reason: String) {
        val app = systemUiApp ?: return
        if (!isUserUnlocked(app)) return
        val attempt = rebindAttempt++
        val delayMs = (500L shl attempt.coerceAtMost(6)).coerceAtMost(MAX_REBIND_DELAY_MS)
        logWarn("Scheduling SystemUI rebind ($reason) in ${delayMs}ms")
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ bindEngineService(app) }, delayMs)
    }

    private fun writeClipOnMain(app: Application, clipBuilder: () -> ClipData) {
        val identity = Binder.clearCallingIdentity()
        try {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return
            lastRemoteWriteAtMs = System.currentTimeMillis()
            cm.setPrimaryClip(clipBuilder())
        } catch (err: Throwable) {
            logWarn("setPrimaryClip failed in SystemUI", err)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun applyExtras(description: ClipDescription, sensitive: Boolean, suppressOverlay: Boolean) {
        val extras = PersistableBundle(description.extras ?: PersistableBundle()).apply {
            putBoolean(EXTRA_SYNC_ORIGIN, true)
            if (suppressOverlay) {
                putBoolean(EXTRA_SUPPRESS_OVERLAY, true)
            }
            if (sensitive) {
                val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    EXTRA_IS_SENSITIVE_COMPAT
                }
                putBoolean(sensitiveKey, true)
            }
        }
        description.extras = extras
    }

    private fun readAndForwardPrimaryClip(ignoreSuppression: Boolean) {
        val app = systemUiApp ?: return
        val nowMs = System.currentTimeMillis()
        if (!ignoreSuppression && nowMs - lastRemoteWriteAtMs < ECHO_GUARD_WINDOW_MS) {
            return
        }

        clipExecutor.execute {
            val cm = app.getSystemService(ClipboardManager::class.java) ?: return@execute
            val clip = runCatching { cm.primaryClip }.getOrNull() ?: return@execute
            val desc = clip.description ?: return@execute
            val extras = desc.extras
            if (!ignoreSuppression && extras?.getBoolean(EXTRA_SYNC_ORIGIN, false) == true) {
                return@execute
            }

            val isSensitive = extras?.getBoolean(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ClipDescription.EXTRA_IS_SENSITIVE
                } else {
                    EXTRA_IS_SENSITIVE_COMPAT
                },
                false
            ) == true

            val uris = mutableListOf<Uri>()
            val textParts = mutableListOf<String>()
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                val itemUri = item.uri
                if (itemUri != null) {
                    uris.add(itemUri)
                } else {
                    val txt = runCatching { item.coerceToText(app)?.toString() }.getOrNull()
                    if (!txt.isNullOrEmpty()) {
                        textParts.add(txt)
                    }
                }
            }

            val combinedText = textParts.joinToString("\n")
            val bridge = engineBridge
            if (bridge == null) {
                pendingClip = if (uris.isNotEmpty()) {
                    PendingClip.Uris(uris.map { it.toString() }, combinedText, nowMs)
                } else if (combinedText.isNotEmpty()) {
                    PendingClip.Text(combinedText, isSensitive, nowMs)
                } else {
                    null
                }
                bindEngineService(app)
                return@execute
            }

            if (uris.isNotEmpty()) {
                forwardUrisToBridge(app, bridge, desc, uris, combinedText, nowMs)
            } else if (combinedText.isNotEmpty()) {
                runCatching {
                    bridge.onClipboardTextChanged(combinedText, isSensitive, nowMs)
                }.onFailure {
                    pendingClip = PendingClip.Text(combinedText, isSensitive, nowMs)
                    scheduleReconnect("onClipboardTextChangedFailed")
                }
            }
        }
    }

    private fun flushPendingClip(bridge: ISyncEngineBridge) {
        val pending = pendingClip ?: return
        pendingClip = null
        val app = systemUiApp ?: return
        when (pending) {
            is PendingClip.Text -> {
                runCatching {
                    bridge.onClipboardTextChanged(pending.text, pending.isSensitive, pending.timestampMs)
                }.onFailure {
                    pendingClip = pending
                }
            }

            is PendingClip.Uris -> {
                val uris = pending.uriStrings.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                if (uris.isNotEmpty()) {
                    forwardUrisToBridge(app, bridge, null, uris, pending.fallbackText, pending.timestampMs)
                }
            }
        }
    }

    private fun forwardUrisToBridge(
        app: Application,
        bridge: ISyncEngineBridge,
        desc: ClipDescription?,
        uris: List<Uri>,
        fallbackText: String,
        timestampMs: Long
    ) {
        val tempFiles = mutableListOf<File>()
        val fds = mutableListOf<ParcelFileDescriptor>()
        val names = mutableListOf<String>()
        val mimes = mutableListOf<String>()

        try {
            uris.forEachIndexed { idx, uri ->
                val mime = resolveMimeType(app, desc, uri, idx)
                val name = resolveFileName(app, uri, mime, idx)
                val tmp = File(app.cacheDir, "scx_clip_${UUID.randomUUID()}")
                val copied = copyUriToTempFile(app, uri, tmp)
                if (copied) {
                    val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                    tempFiles.add(tmp)
                    fds.add(pfd)
                    names.add(name)
                    mimes.add(mime)
                } else {
                    runCatching { tmp.delete() }
                }
            }

            if (fds.isNotEmpty()) {
                bridge.onClipboardFilesChanged(fds, names, mimes, fallbackText, timestampMs)
            } else if (fallbackText.isNotEmpty()) {
                bridge.onClipboardTextChanged(fallbackText, false, timestampMs)
            }
        } catch (err: Throwable) {
            logWarn("forwardUrisToBridge failed", err)
            scheduleReconnect("forwardUrisFailed")
        } finally {
            fds.forEach { runCatching { it.close() } }
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    private fun copyUriToTempFile(app: Application, uri: Uri, dest: File): Boolean = runCatching {
        app.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SINGLE_FILE_BYTES) {
                        return@runCatching false
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            true
        } ?: false
    }.getOrDefault(false)

    private fun resolveMimeType(app: Application, desc: ClipDescription?, uri: Uri, idx: Int): String {
        val fromResolver = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return fromResolver
        if (desc != null && idx < desc.mimeTypeCount) {
            val fromDesc = desc.getMimeType(idx)
            if (!fromDesc.isNullOrBlank() && fromDesc != "*/*") return fromDesc
        }
        return "application/octet-stream"
    }

    private fun resolveFileName(app: Application, uri: Uri, mime: String, idx: Int): String {
        val queried = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val rawName = queried ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val cleanName = rawName?.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")?.takeIf { it.isNotBlank() }
        if (cleanName != null && cleanName.contains('.')) return cleanName

        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val base = cleanName ?: "clipboard_${idx + 1}"
        return if (!ext.isNullOrBlank()) "$base.$ext" else base
    }

    private fun installClipboardOverlaySuppressor(
        module: XposedInterface,
        app: Application,
        classLoader: ClassLoader
    ) {
        val candidateClasses = listOf(
            "com.android.systemui.clipboardoverlay.ClipboardListener",
            "com.android.systemui.clipboardoverlay.ClipboardOverlayController"
        )
        for (clsName in candidateClasses) {
            val cls = runCatching { Class.forName(clsName, false, classLoader) }.getOrNull() ?: continue
            runCatching {
                cls.declaredMethods
                    .filter { it.name == "onPrimaryClipChanged" || it.name == "shouldSuppressOverlay" }
                    .forEach { method ->
                        val handle = module.hook(method)
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .intercept { chain ->
                                if (shouldSuppressClipboardOverlay(app)) {
                                    return@intercept if (method.returnType == Boolean::class.javaPrimitiveType) {
                                        true
                                    } else {
                                        null
                                    }
                                }
                                chain.proceed()
                            }
                        hookHandles.add(handle)
                    }
            }.onFailure { err ->
                logWarn("ClipboardOverlay hook failed on $clsName", err)
            }
        }
    }

    private fun shouldSuppressClipboardOverlay(app: Application): Boolean {
        val pm = app.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return true
        if (System.currentTimeMillis() - lastRemoteWriteAtMs < ECHO_GUARD_WINDOW_MS) {
            val cm = app.getSystemService(ClipboardManager::class.java)
            val extras = runCatching { cm?.primaryClipDescription?.extras }.getOrNull()
            if (extras?.getBoolean(EXTRA_SUPPRESS_OVERLAY, false) == true) {
                return true
            }
        }
        return false
    }

    private fun isUserUnlocked(context: Context): Boolean {
        val um = context.getSystemService(UserManager::class.java) ?: return true
        return runCatching { um.isUserUnlocked }.getOrDefault(true)
    }

    private fun currentApplication(): Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        at.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

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
