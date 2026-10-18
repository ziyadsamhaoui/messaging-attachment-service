package com.ziyadsamhaoui.messagingattachmentservice.service;

import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AttachmentJobs {

    private static final Logger log = LoggerFactory.getLogger(AttachmentJobs.class);
    private static final int RETRY_BATCH = 20;

    private final AttachmentRepository attachmentRepository;
    private final AttachmentLifecycleService lifecycleService;
    private final AttachmentProperties properties;

    public AttachmentJobs(AttachmentRepository attachmentRepository,
            AttachmentLifecycleService lifecycleService, AttachmentProperties properties) {
        this.attachmentRepository = attachmentRepository;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${badrlink.attachment.scan.interval:5s}")
    public void scanPending() {
        for (UUID attachmentId : attachmentRepository.findPendingScanIds(properties.scan().batchSize())) {
            try {
                lifecycleService.scan(attachmentId);
            } catch (RuntimeException exception) {
                log.error("Scan worker failed for attachment {}", attachmentId, exception);
            }
        }
    }

    @Scheduled(fixedDelayString = "${badrlink.attachment.scan.sync-interval:30s}")
    public void retryChatSync() {
        for (Attachment attachment : attachmentRepository.findUnsyncedReady(RETRY_BATCH)) {
            try {
                lifecycleService.syncReady(attachment.getId());
            } catch (RuntimeException exception) {
                log.error("Chat sync retry failed for attachment {}", attachment.getId(), exception);
            }
        }
    }

    @Scheduled(fixedDelayString = "${badrlink.attachment.scan.quarantine-interval:30s}")
    public void retryQuarantine() {
        for (Attachment attachment : attachmentRepository.findUnquarantinedRejected(RETRY_BATCH)) {
            try {
                lifecycleService.quarantine(attachment.getId());
            } catch (RuntimeException exception) {
                log.error("Quarantine retry failed for attachment {}", attachment.getId(), exception);
            }
        }
    }
}
