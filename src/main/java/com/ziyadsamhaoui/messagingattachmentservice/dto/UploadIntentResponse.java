package com.ziyadsamhaoui.messagingattachmentservice.dto;

import java.time.Instant;
import java.util.UUID;

public record UploadIntentResponse(
        UUID attachmentId,
        String uploadUrl,
        Instant expiresAt,
        String status,
        String fileName,
        String fileType,
        long fileSizeBytes) {
}
