package com.ziyadsamhaoui.messagingattachmentservice.outbox;

public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED_DEAD_LETTER
}
