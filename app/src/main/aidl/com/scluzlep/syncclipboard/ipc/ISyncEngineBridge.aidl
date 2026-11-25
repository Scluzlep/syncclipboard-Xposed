package com.scluzlep.syncclipboard.ipc;

import android.os.ParcelFileDescriptor;
import com.scluzlep.syncclipboard.ipc.ISystemUiClipboardClient;

interface ISyncEngineBridge {
    void registerSystemServerHeartbeat(IBinder token, boolean dozeWhitelisted);
    void registerClipboardClient(ISystemUiClipboardClient client);
    void unregisterClipboardClient(ISystemUiClipboardClient client);
    void onClipboardTextChanged(String text, boolean isSensitive, long timestampMs);
    void onClipboardFilesChanged(
        in List<ParcelFileDescriptor> fds,
        in List<String> fileNames,
        in List<String> mimeTypes,
        String fallbackText,
        long timestampMs
    );
    void onOtpNotificationCandidate(String sourcePackage, String body, long timestampMs);
}
