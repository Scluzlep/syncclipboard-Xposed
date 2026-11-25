package com.scluzlep.syncclipboard.ipc;

interface ISystemUiClipboardClient {
    void writeTextToClipboard(String text, boolean sensitive, boolean suppressOverlay);
    void writeUriToClipboard(String uriString, String mimeType, String label, boolean sensitive, boolean suppressOverlay);
    void requestCurrentClipboardPush();
    void ping();
}
