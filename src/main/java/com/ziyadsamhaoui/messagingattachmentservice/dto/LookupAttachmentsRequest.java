package com.ziyadsamhaoui.messagingattachmentservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record LookupAttachmentsRequest(
        @NotEmpty(message = "ids must not be empty")
        @Size(max = 100, message = "at most 100 attachments are looked up per request")
        List<UUID> ids) {
}
