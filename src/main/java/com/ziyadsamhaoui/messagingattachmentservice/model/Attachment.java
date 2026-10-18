package com.ziyadsamhaoui.messagingattachmentservice.model;

import com.ziyadsamhaoui.messagingattachmentservice.model.enums.AttachmentKind;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.MalwareStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.SupportedFileType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "attachments")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Attachment {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "room_id", nullable = false, length = 100)
    private String roomId;

    @Column(name = "message_id", length = 100)
    private String messageId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 20)
    private SupportedFileType fileType;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private AttachmentKind kind;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FileStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "malware_status", length = 20)
    private MalwareStatus malwareStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", length = 20)
    private ModerationStatus moderationStatus;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "chat_synced_at")
    private Instant chatSyncedAt;

    @Column(name = "quarantined_at")
    private Instant quarantinedAt;

    @Column(name = "scan_attempts", nullable = false)
    private int scanAttempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onPrePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = FileStatus.PENDING_UPLOAD;
        }
    }

    public boolean isDownloadable() {
        return status == FileStatus.READY;
    }
}
