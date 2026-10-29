package com.ziyadsamhaoui.messagingattachmentservice.service;

import com.ziyadsamhaoui.messagingattachmentservice.client.ChatServiceClient;
import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.MalwareStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.AttachmentEvents;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.TransactionalOutboxPublisher;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import com.ziyadsamhaoui.messagingattachmentservice.scanner.ContentModerator;
import com.ziyadsamhaoui.messagingattachmentservice.scanner.MalwareScanner;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorage;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorageException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AttachmentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentLifecycleService.class);

    private final AttachmentRepository attachmentRepository;
    private final ObjectStorage objectStorage;
    private final MalwareScanner malwareScanner;
    private final ContentModerator contentModerator;
    private final ChatServiceClient chatServiceClient;
    private final AttachmentProperties properties;
    private final TransactionalOutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Timer readyLatency;

    public AttachmentLifecycleService(AttachmentRepository attachmentRepository, ObjectStorage objectStorage,
            MalwareScanner malwareScanner, ContentModerator contentModerator, ChatServiceClient chatServiceClient,
            AttachmentProperties properties, TransactionalOutboxPublisher outboxPublisher,
            TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.attachmentRepository = attachmentRepository;
        this.objectStorage = objectStorage;
        this.malwareScanner = malwareScanner;
        this.contentModerator = contentModerator;
        this.chatServiceClient = chatServiceClient;
        this.properties = properties;
        this.outboxPublisher = outboxPublisher;
        this.transactionTemplate = transactionTemplate;
        this.readyLatency = Timer.builder("badrlink.attachment.ready.latency")
                .description("Time from upload confirmation to the attachment becoming READY")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public enum ScanOutcome {
        READY,
        REJECTED,
        RETRY,
        IGNORED
    }

    public ScanOutcome scan(UUID attachmentId) {
        Attachment snapshot = transactionTemplate.execute(status -> attachmentRepository.lockById(attachmentId)
                .filter(attachment -> attachment.getStatus() == FileStatus.PENDING_SCAN)
                .orElse(null));

        if (snapshot == null) {
            return ScanOutcome.IGNORED;
        }

        MalwareStatus malware;
        ModerationStatus moderation;
        try {
            malware = malwareScanner.scan(snapshot);
            moderation = contentModerator.moderate(snapshot);
        } catch (RuntimeException exception) {
            log.warn("Scan of attachment {} failed: {}", attachmentId, exception.getMessage());
            ScanOutcome outcome = transactionTemplate.execute(status -> recordScanFailure(attachmentId, exception));
            return outcome == null ? ScanOutcome.RETRY : outcome;
        }

        ScanOutcome outcome = transactionTemplate.execute(status -> applyVerdict(attachmentId, malware, moderation));
        if (outcome == null) {
            return ScanOutcome.IGNORED;
        }
        if (outcome == ScanOutcome.READY) {
            syncReady(attachmentId);
        } else if (outcome == ScanOutcome.REJECTED) {
            quarantine(attachmentId);
        }
        return outcome;
    }

    private ScanOutcome applyVerdict(UUID attachmentId, MalwareStatus malware, ModerationStatus moderation) {
        Attachment attachment = attachmentRepository.lockById(attachmentId).orElse(null);
        if (attachment == null || attachment.getStatus() != FileStatus.PENDING_SCAN) {
            return ScanOutcome.IGNORED;
        }

        attachment.setMalwareStatus(malware);
        attachment.setModerationStatus(moderation);
        attachment.setScannedAt(Instant.now());
        attachment.setLastError(null);

        boolean safe = malware == MalwareStatus.SAFE && moderation == ModerationStatus.SFW;
        attachment.setStatus(safe ? FileStatus.READY : FileStatus.REJECTED);
        attachmentRepository.save(attachment);

        if (safe) {
            recordReadyLatency(attachment);
            outboxPublisher.publish(attachment.getId().toString(), AttachmentEvents.FILE_READY,
                    new AttachmentEvents.FileReady(attachment.getId().toString(), attachment.getRoomId(),
                            attachment.getMessageId(), attachment.getUserId().toString(),
                            attachment.getFileType().mimeType(), attachment.getFileSizeBytes(),
                            attachment.getScannedAt()));
            return ScanOutcome.READY;
        }

        outboxPublisher.publish(attachment.getId().toString(), AttachmentEvents.FILE_REJECTED,
                new AttachmentEvents.FileRejected(attachment.getId().toString(), attachment.getRoomId(),
                        attachment.getMessageId(), attachment.getUserId().toString(), attachment.getFileName(),
                        malware.name(), moderation.name(), attachment.getScannedAt()));
        return ScanOutcome.REJECTED;
    }

    private void recordReadyLatency(Attachment attachment) {
        Instant uploadedAt = attachment.getUploadedAt();
        Instant scannedAt = attachment.getScannedAt();
        if (uploadedAt == null || scannedAt == null || scannedAt.isBefore(uploadedAt)) {
            return;
        }
        readyLatency.record(Duration.between(uploadedAt, scannedAt));
    }

    private ScanOutcome recordScanFailure(UUID attachmentId, RuntimeException exception) {
        Attachment attachment = attachmentRepository.lockById(attachmentId).orElse(null);
        if (attachment == null || attachment.getStatus() != FileStatus.PENDING_SCAN) {
            return ScanOutcome.IGNORED;
        }

        int attempts = attachment.getScanAttempts() + 1;
        attachment.setScanAttempts(attempts);
        attachment.setLastError(truncate(exception.getMessage()));

        if (attempts < properties.scan().maxAttempts()) {
            attachmentRepository.save(attachment);
            return ScanOutcome.RETRY;
        }

        attachment.setScannedAt(Instant.now());
        attachment.setStatus(FileStatus.REJECTED);
        attachmentRepository.save(attachment);
        outboxPublisher.publish(attachment.getId().toString(), AttachmentEvents.FILE_REJECTED,
                new AttachmentEvents.FileRejected(attachment.getId().toString(), attachment.getRoomId(),
                        attachment.getMessageId(), attachment.getUserId().toString(), attachment.getFileName(),
                        attachment.getMalwareStatus() == null ? null : attachment.getMalwareStatus().name(),
                        attachment.getModerationStatus() == null ? null : attachment.getModerationStatus().name(),
                        attachment.getScannedAt()));
        return ScanOutcome.REJECTED;
    }

    public boolean syncReady(UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null || attachment.getStatus() != FileStatus.READY
                || attachment.getChatSyncedAt() != null || !StringUtils.hasText(attachment.getMessageId())) {
            return false;
        }

        try {
            chatServiceClient.updateMessageAttachments(attachment.getMessageId(),
                    List.of(attachment.getId().toString()));
        } catch (RuntimeException exception) {
            log.warn("Chat sync for attachment {} failed; it will be retried: {}",
                    attachmentId, exception.getMessage());
            return false;
        }

        Boolean updated = transactionTemplate.execute(status -> {
            Attachment locked = attachmentRepository.lockById(attachmentId).orElse(null);
            if (locked == null || locked.getStatus() != FileStatus.READY
                    || locked.getChatSyncedAt() != null) {
                return false;
            }
            locked.setChatSyncedAt(Instant.now());
            attachmentRepository.save(locked);
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    public boolean quarantine(UUID attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
        if (attachment == null || attachment.getStatus() != FileStatus.REJECTED
                || attachment.getQuarantinedAt() != null) {
            return false;
        }

        try {
            objectStorage.copyToQuarantine(attachment.getObjectKey());
            objectStorage.delete(attachment.getObjectKey());
        } catch (ObjectStorageException exception) {
            log.warn("Quarantine move for attachment {} failed; it will be retried: {}",
                    attachmentId, exception.getMessage());
            return false;
        }

        Boolean updated = transactionTemplate.execute(status -> {
            Attachment locked = attachmentRepository.lockById(attachmentId).orElse(null);
            if (locked == null || locked.getStatus() != FileStatus.REJECTED
                    || locked.getQuarantinedAt() != null) {
                return false;
            }
            locked.setQuarantinedAt(Instant.now());
            attachmentRepository.save(locked);
            return true;
        });
        return Boolean.TRUE.equals(updated);
    }

    public int deleteForMessage(String messageId) {
        List<Attachment> attachments = attachmentRepository.findByMessageIdOrderByCreatedAtAsc(messageId);
        if (attachments.isEmpty()) {
            return 0;
        }

        for (Attachment attachment : attachments) {
            objectStorage.delete(attachment.getObjectKey());
            objectStorage.deleteFromQuarantine(attachment.getObjectKey());
        }

        transactionTemplate.executeWithoutResult(status -> attachmentRepository.deleteAll(attachments));
        log.debug("Removed {} attachment(s) for deleted message {}", attachments.size(), messageId);
        return attachments.size();
    }

    private static String truncate(String message) {
        if (message == null) {
            return "scan failed";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
