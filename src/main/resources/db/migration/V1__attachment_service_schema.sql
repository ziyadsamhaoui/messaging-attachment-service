CREATE TABLE attachments (
    id                 UUID PRIMARY KEY,
    user_id            UUID NOT NULL,
    room_id            VARCHAR(100) NOT NULL,
    message_id         VARCHAR(100),
    file_name          VARCHAR(255) NOT NULL,
    file_type          VARCHAR(20) NOT NULL,
    kind               VARCHAR(20) NOT NULL,
    file_size_bytes    BIGINT NOT NULL,
    object_key         VARCHAR(255) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    malware_status     VARCHAR(20),
    moderation_status  VARCHAR(20),
    uploaded_at        TIMESTAMPTZ,
    scanned_at         TIMESTAMPTZ,
    chat_synced_at     TIMESTAMPTZ,
    quarantined_at     TIMESTAMPTZ,
    scan_attempts      INT NOT NULL DEFAULT 0,
    last_error         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT attachments_status_check
        CHECK (status IN ('PENDING_UPLOAD', 'PENDING_SCAN', 'READY', 'REJECTED')),
    CONSTRAINT attachments_kind_check
        CHECK (kind IN ('IMAGE', 'VIDEO', 'AUDIO', 'FILE')),
    CONSTRAINT attachments_malware_status_check
        CHECK (malware_status IS NULL OR malware_status IN ('SAFE', 'UNSAFE')),
    CONSTRAINT attachments_moderation_status_check
        CHECK (moderation_status IS NULL OR moderation_status IN ('SFW', 'NSFW')),
    CONSTRAINT attachments_file_size_check
        CHECK (file_size_bytes > 0)
);

CREATE UNIQUE INDEX ux_attachments_object_key ON attachments (object_key);
CREATE INDEX ix_attachments_message ON attachments (message_id);
CREATE INDEX ix_attachments_room_ready ON attachments (room_id, uploaded_at DESC, id DESC)
    WHERE status = 'READY';
CREATE INDEX ix_attachments_pending_scan ON attachments (created_at)
    WHERE status = 'PENDING_SCAN';
CREATE INDEX ix_attachments_chat_sync ON attachments (scanned_at)
    WHERE status = 'READY' AND chat_synced_at IS NULL;
CREATE INDEX ix_attachments_quarantine ON attachments (scanned_at)
    WHERE status = 'REJECTED' AND quarantined_at IS NULL;
