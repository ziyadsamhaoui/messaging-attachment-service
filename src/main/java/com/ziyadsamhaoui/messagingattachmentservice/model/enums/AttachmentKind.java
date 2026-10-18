package com.ziyadsamhaoui.messagingattachmentservice.model.enums;

public enum AttachmentKind {
    IMAGE,
    VIDEO,
    AUDIO,
    FILE;

    public static AttachmentKind fromMimeType(String mimeType) {
        if (mimeType == null) {
            return FILE;
        }
        String normalized = mimeType.trim().toLowerCase();
        int parameters = normalized.indexOf(';');
        if (parameters >= 0) {
            normalized = normalized.substring(0, parameters).trim();
        }
        if (normalized.startsWith("image/")) {
            return IMAGE;
        }
        if (normalized.startsWith("video/")) {
            return VIDEO;
        }
        if (normalized.startsWith("audio/")) {
            return AUDIO;
        }
        return FILE;
    }
}
