package com.ziyadsamhaoui.messagingattachmentservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadIntentRequest(
        @NotBlank(message = "roomId is required")
        @Size(max = 100, message = "roomId is too long")
        String roomId,

        @NotBlank(message = "messageId is required")
        @Size(max = 100, message = "messageId is too long")
        String messageId,

        @NotBlank(message = "fileName is required")
        @Size(max = 255, message = "fileName is too long")
        String fileName,

        @NotBlank(message = "fileType is required")
        @Size(max = 150, message = "fileType is too long")
        String fileType,

        @Positive(message = "fileSizeBytes must be positive")
        long fileSizeBytes) {
}
