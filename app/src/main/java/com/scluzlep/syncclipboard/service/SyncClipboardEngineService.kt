package com.scluzlep.syncclipboard.service

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.scluzlep.syncclipboard.SyncClipboardApp
import com.scluzlep.syncclipboard.ipc.ISyncEngineBridge
import com.scluzlep.syncclipboard.ipc.ISystemUiClipboardClient

/**
 * Privileged IPC boundary service in `com.scluzlep.syncclipboard` bound by `system_server` (UID 1000)
 * and `com.android.systemui`. Intentionally exported without a self-signed signature permission;
 * every Binder entry enforces strict UID + system-flag verification.
 */
class SyncClipboardEngineService : Service() {

    private val guardBinder = object : ISyncEngineBridge.Stub() {
        override fun registerSystemServerHeartbeat(token: IBinder?, dozeWhitelisted: Boolean) {
            enforceAllowedCaller(requireSystemUidOnly = true)
            bridgeOrNull()?.registerSystemServerHeartbeat(token, dozeWhitelisted)
        }

        override fun registerClipboardClient(client: ISystemUiClipboardClient?) {
            enforceAllowedCaller(requireSystemUidOnly = false)
            bridgeOrNull()?.registerClipboardClient(client)
        }

        override fun unregisterClipboardClient(client: ISystemUiClipboardClient?) {
            enforceAllowedCaller(requireSystemUidOnly = false)
            bridgeOrNull()?.unregisterClipboardClient(client)
        }

        override fun onClipboardTextChanged(text: String?, isSensitive: Boolean, timestampMs: Long) {
            enforceAllowedCaller(requireSystemUidOnly = false)
            bridgeOrNull()?.onClipboardTextChanged(text, isSensitive, timestampMs)
        }

        override fun onClipboardFilesChanged(
            fds: MutableList<ParcelFileDescriptor>?,
            fileNames: MutableList<String>?,
            mimeTypes: MutableList<String>?,
            fallbackText: String?,
            timestampMs: Long
        ) {
            enforceAllowedCaller(requireSystemUidOnly = false)
            val bridge = bridgeOrNull()
            if (bridge == null) {
                fds?.forEach { runCatching { it.close() } }
                return
            }
            bridge.onClipboardFilesChanged(fds, fileNames, mimeTypes, fallbackText, timestampMs)
        }

        override fun onOtpNotificationCandidate(sourcePackage: String?, body: String?, timestampMs: Long) {
            enforceAllowedCaller(requireSystemUidOnly = true)
            bridgeOrNull()?.onOtpNotificationCandidate(sourcePackage, body, timestampMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder = guardBinder

    private fun bridgeOrNull(): ISyncEngineBridge.Stub? =
        (applicationContext as? SyncClipboardApp)?.engineBridge

    private fun enforceAllowedCaller(requireSystemUidOnly: Boolean) {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.SYSTEM_UID) return
        if (requireSystemUidOnly) {
            throw SecurityException("Only system_server (UID 1000) may call this method; got UID=$callingUid")
        }

        val packages = runCatching { packageManager.getPackagesForUid(callingUid) }.getOrNull()
        val isTrustedSystemUi = packages?.any { pkg ->
            if (pkg != PACKAGE_SYSTEM_UI) return@any false
            val appInfo = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@any false
            (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } == true

        if (!isTrustedSystemUi) {
            throw SecurityException("Unauthorized caller UID=$callingUid")
        }
    }

    companion object {
        private const val PACKAGE_SYSTEM_UI = "com.android.systemui"
    }
}
