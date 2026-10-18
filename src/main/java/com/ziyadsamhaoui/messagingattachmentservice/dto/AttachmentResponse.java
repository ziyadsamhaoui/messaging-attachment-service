package com.ziyadsamhaoui.messagingattachmentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentResponse(
        UUID id,
        String roomId,
        String messageId,
        String fileName,
        String fileType,
        String kind,
        long fileSizeBytes,
        String status,
        String malwareStatus,
        String moderationStatus,
        Instant uploadedAt,
        Instant scannedAt,
        String url,
        Instant urlExpiresAt) {

    public static AttachmentResponse of(Attachment attachment, String url, Instant urlExpiresAt) {
        return new AttachmentResponse(
                attachment.getId(),
                attachment.getRoomId(),
                attachment.getMessageId(),
                attachment.getFileName(),
                attachment.getFileType().mimeType(),
                attachment.getKind().name(),
                attachment.getFileSizeBytes(),
                attachment.getStatus().name(),
                attachment.getMalwareStatus() == null ? null : attachment.getMalwareStatus().name(),
                attachment.getModerationStatus() == null ? null : attachment.getModerationStatus().name(),
                attachment.getUploadedAt(),
                attachment.getScannedAt(),
                url,
                urlExpiresAt);
    }
}
