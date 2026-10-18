package com.ziyadsamhaoui.messagingattachmentservice.dto;

import java.time.Instant;

public record DownloadUrlResponse(String fileName, String fileType, long fileSizeBytes, String url,
        Instant expiresAt) {
}
