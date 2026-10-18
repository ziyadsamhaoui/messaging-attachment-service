package com.ziyadsamhaoui.messagingattachmentservice.outbox;

import java.time.Instant;

public final class AttachmentEvents {

    private AttachmentEvents() {
    }

    public static final String TOPIC = "badrlink.attachment.file.v1";
    public static final String AGGREGATE_TYPE = "Attachment";

    public static final String FILE_UPLOADED = "FILE_UPLOADED";
    public static final String FILE_READY = "FILE_READY";
    public static final String FILE_REJECTED = "FILE_REJECTED";

    public record FileUploaded(String attachmentId, String roomId, String messageId, String userId,
            String fileName, String fileType, long fileSizeBytes, Instant uploadedAt) {
    }

    public record FileReady(String attachmentId, String roomId, String messageId, String userId,
            String fileType, long fileSizeBytes, Instant scannedAt) {
    }

    public record FileRejected(String attachmentId, String roomId, String messageId, String userId,
            String fileName, String malwareStatus, String moderationStatus, Instant scannedAt) {
    }
}
