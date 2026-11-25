package com.scluzlep.syncclipboard.xposed

import android.app.Notification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * `system_server` notification hooker capturing OTP candidates at
 * `NotificationManagerService.enqueueNotificationInternal` before Android 15+
 * `SensitiveNotificationProtectionManagerService` or HyperOS redaction.
 *
 * Security & privacy invariants:
 * - Zero plaintext notification or OTP logging (logs source package + char length only).
 * - 5-second body deduplication inside `system_server`.
 * - Fast zero-regex filter requiring >= 4 consecutive digits + CN/EN verification keyword.
 * - Forwards directly via `SystemKeepAliveHooker` Binder to `SyncClipboardEngineService`.
 */
object SystemNotificationHooker {

    private const val TAG = "SyncNotifHook"
    private const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"
    private const val TARGET_PKG = "com.scluzlep.syncclipboard"
    private const val SYSTEM_UI_PKG = "com.android.systemui"
    private const val ANDROID_PKG = "android"

    private const val BODY_DEDUP_MS = 5_000L
    private const val BODY_DEDUP_MAX = 256

    private val CODE_KEYWORDS = arrayOf(
        "验证码", "动态码", "授权码", "校验码", "验证代码", "动态口令", "口令",
        "一次性", "确认码", "安全码", "代码", "verification", "passcode",
        "security", "otp", "code", "pin", "auth"
    )

    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val recentBodies = HashMap<String, Long>()
    private val forwardExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scx-notif-forward").apply { isDaemon = true }
    }

    @Volatile
    var bootClassLoader: ClassLoader? = null
        private set

    @Volatile
    private var moduleRef: XposedInterface? = null

    @Volatile
    private var installed = false

    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader?) {
        moduleRef = module
        if (classLoader != null) bootClassLoader = classLoader
        if (installed) return

        val loader = resolveNmsLoader(classLoader)
        bootClassLoader = loader

        runCatching {
            val nmsClass = Class.forName(NMS_CLASS, false, loader)
            var hookedAny = false
            nmsClass.declaredMethods
                .filter { m ->
                    m.name == "enqueueNotificationInternal" &&
                        m.parameterTypes.any { it == Notification::class.java }
                }
                .forEach { method ->
                    val handle = module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .intercept { chain -> captureAndProceed(chain) }
                    hookHandles.add(handle)
                    hookedAny = true
                }
            if (hookedAny) {
                installed = true
                logInfo("NMS.enqueueNotificationInternal hooked")
            } else {
                logWarn("NMS.enqueueNotificationInternal(Notification) not found")
            }
        }.onFailure { err ->
            logWarn("Failed to hook NMS", err)
        }
    }

    @Synchronized
    fun unhook() {
        hookHandles.forEach { runCatching { it.unhook() } }
        hookHandles.clear()
        installed = false
    }

    private fun captureAndProceed(chain: XposedInterface.Chain): Any? {
        runCatching {
            val args = chain.args
            val notifIdx = chain.executable.parameterTypes.indexOfFirst { it == Notification::class.java }
            if (notifIdx in args.indices) {
                val notif = args[notifIdx] as? Notification
                val pkg = (args.firstOrNull { it is String } as? String) ?: "?"
                if (notif != null) {
                    handleNotification(pkg, notif)
                }
            }
        }
        return chain.proceed()
    }

    private fun handleNotification(pkg: String, notif: Notification) {
        if (pkg == SYSTEM_UI_PKG || pkg == ANDROID_PKG || pkg == TARGET_PKG) return

        val body = buildBody(notif)
        if (body.isBlank()) return
        if (!claimBody(pkg, body)) return
        if (!fastLooksLikeCode(body)) return

        val capturedAt = System.currentTimeMillis()
        val bodyLen = body.length
        forwardExecutor.execute {
            val bridge = SystemKeepAliveHooker.getBridge()
            if (bridge == null) {
                SystemKeepAliveHooker.ensureBoundAsync()
                logWarn("Engine bridge unavailable for OTP candidate (pkg=$pkg, len=$bodyLen)")
                return@execute
            }
            runCatching {
                bridge.onOtpNotificationCandidate(pkg, body, capturedAt)
                logInfo("Forwarded OTP candidate (pkg=$pkg, len=$bodyLen)")
            }.onFailure { err ->
                logWarn("OTP candidate Binder forward failed (pkg=$pkg, len=$bodyLen)", err)
                SystemKeepAliveHooker.ensureBoundAsync()
            }
        }
    }

    private fun buildBody(notif: Notification): String = runCatching {
        val extras = notif.extras ?: return ""
        val sb = StringBuilder(128)
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            if (!line.isNullOrBlank()) sb.append(line).append(' ')
        }
        notif.tickerText?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append(' ')
        }
        sb.toString().trim()
    }.getOrDefault("")

    @Synchronized
    private fun claimBody(pkg: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        if (recentBodies.size > BODY_DEDUP_MAX) {
            val it = recentBodies.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > BODY_DEDUP_MS) it.remove()
            }
        }
        val key = "$pkg\u0001${body.length}\u0001${body.hashCode()}"
        val last = recentBodies[key]
        if (last != null && now - last < BODY_DEDUP_MS) return false
        recentBodies[key] = now
        return true
    }

    private fun fastLooksLikeCode(body: String): Boolean {
        var run = 0
        for (c in body) {
            if (c in '0'..'9') {
                if (++run >= 4) break
            } else {
                run = 0
            }
        }
        if (run < 4) return false
        val lower = body.lowercase()
        for (kw in CODE_KEYWORDS) {
            if (lower.contains(kw)) return true
        }
        return false
    }

    private fun resolveNmsLoader(provided: ClassLoader?): ClassLoader {
        val candidates = mutableListOf<ClassLoader>()
        if (provided != null) candidates.add(provided)
        runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let { candidates.add(it) }
        var loader = SystemNotificationHooker::class.java.classLoader
        while (loader != null) {
            candidates.add(loader)
            loader = loader.parent
        }
        for (candidate in candidates) {
            if (runCatching { Class.forName(NMS_CLASS, false, candidate) }.isSuccess) {
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
