package com.ziyadsamhaoui.messagingattachmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ziyadsamhaoui.messagingattachmentservice.client.ChatServiceClient;
import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.exception.DependencyUnavailableException;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.AttachmentKind;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.MalwareStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.SupportedFileType;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.AttachmentEvents;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.TransactionalOutboxPublisher;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import com.ziyadsamhaoui.messagingattachmentservice.scanner.ContentModerator;
import com.ziyadsamhaoui.messagingattachmentservice.scanner.MalwareScanner;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorage;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorageException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AttachmentLifecycleServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private AttachmentRepository attachmentRepository;
    private ObjectStorage objectStorage;
    private MalwareScanner malwareScanner;
    private ContentModerator contentModerator;
    private ChatServiceClient chatServiceClient;
    private TransactionalOutboxPublisher outboxPublisher;
    private AttachmentProperties properties;
    private AttachmentLifecycleService service;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(AttachmentRepository.class);
        objectStorage = mock(ObjectStorage.class);
        malwareScanner = mock(MalwareScanner.class);
        contentModerator = mock(ContentModerator.class);
        chatServiceClient = mock(ChatServiceClient.class);
        outboxPublisher = mock(TransactionalOutboxPublisher.class);
        properties = AttachmentUploadServiceTest.properties(3);
        service = new AttachmentLifecycleService(attachmentRepository, objectStorage, malwareScanner,
                contentModerator, chatServiceClient, properties, outboxPublisher,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void aCleanScanMakesTheFileReadySyncsChatAndPublishesFileReady() {
        Attachment attachment = attachment(FileStatus.PENDING_SCAN, 0);
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(malwareScanner.scan(attachment)).thenReturn(MalwareStatus.SAFE);
        when(contentModerator.moderate(attachment)).thenReturn(ModerationStatus.SFW);

        AttachmentLifecycleService.ScanOutcome outcome = service.scan(attachment.getId());

        assertThat(outcome).isEqualTo(AttachmentLifecycleService.ScanOutcome.READY);
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.READY);
        assertThat(attachment.getMalwareStatus()).isEqualTo(MalwareStatus.SAFE);
        assertThat(attachment.getModerationStatus()).isEqualTo(ModerationStatus.SFW);
        assertThat(attachment.getScannedAt()).isNotNull();
        assertThat(attachment.getChatSyncedAt()).isNotNull();
        verify(outboxPublisher).publish(eq(attachment.getId().toString()), eq(AttachmentEvents.FILE_READY),
                any(AttachmentEvents.FileReady.class));
        verify(chatServiceClient).updateMessageAttachments("message-1",
                List.of(attachment.getId().toString()));
        verify(objectStorage, never()).copyToQuarantine(any());
    }

    @Test
    void anUnsafeFileIsRejectedQuarantinedAndNeverSynced() {
        Attachment attachment = attachment(FileStatus.PENDING_SCAN, 0);
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(malwareScanner.scan(attachment)).thenReturn(MalwareStatus.UNSAFE);
        when(contentModerator.moderate(attachment)).thenReturn(ModerationStatus.SFW);

        AttachmentLifecycleService.ScanOutcome outcome = service.scan(attachment.getId());

        assertThat(outcome).isEqualTo(AttachmentLifecycleService.ScanOutcome.REJECTED);
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.REJECTED);
        assertThat(attachment.getQuarantinedAt()).isNotNull();
        verify(objectStorage).copyToQuarantine(attachment.getObjectKey());
        verify(objectStorage).delete(attachment.getObjectKey());
        verify(chatServiceClient, never()).updateMessageAttachments(any(), anyList());
        verify(outboxPublisher).publish(eq(attachment.getId().toString()), eq(AttachmentEvents.FILE_REJECTED),
                any(AttachmentEvents.FileRejected.class));
    }

    @Test
    void contentModerationCanRejectAFileThatIsNotMalware() {
        Attachment attachment = attachment(FileStatus.PENDING_SCAN, 0);
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(malwareScanner.scan(attachment)).thenReturn(MalwareStatus.SAFE);
        when(contentModerator.moderate(attachment)).thenReturn(ModerationStatus.NSFW);

        AttachmentLifecycleService.ScanOutcome outcome = service.scan(attachment.getId());

        assertThat(outcome).isEqualTo(AttachmentLifecycleService.ScanOutcome.REJECTED);
        assertThat(attachment.getMalwareStatus()).isEqualTo(MalwareStatus.SAFE);
        assertThat(attachment.getModerationStatus()).isEqualTo(ModerationStatus.NSFW);
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.REJECTED);
    }

    @Test
    void aFailedQuarantineMoveLeavesTheRejectionInPlaceForRetry() {
        Attachment attachment = attachment(FileStatus.REJECTED, 0);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        doThrow(new ObjectStorageException("minio down", null))
                .when(objectStorage).copyToQuarantine(attachment.getObjectKey());

        assertThat(service.quarantine(attachment.getId())).isFalse();
        assertThat(attachment.getQuarantinedAt()).isNull();
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.REJECTED);
        verify(objectStorage, never()).delete(anyString());

        doNothing().when(objectStorage).copyToQuarantine(attachment.getObjectKey());
        assertThat(service.quarantine(attachment.getId())).isTrue();
        assertThat(attachment.getQuarantinedAt()).isNotNull();
    }

    @Test
    void aScanFailureKeepsTheFilePendingUntilTheAttemptLimitThenRejectsIt() {
        Attachment attachment = attachment(FileStatus.PENDING_SCAN, 0);
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(malwareScanner.scan(attachment)).thenThrow(new IllegalStateException("scanner offline"));

        assertThat(service.scan(attachment.getId()))
                .isEqualTo(AttachmentLifecycleService.ScanOutcome.RETRY);
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.PENDING_SCAN);
        assertThat(attachment.getScanAttempts()).isEqualTo(1);

        service.scan(attachment.getId());
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.PENDING_SCAN);

        AttachmentLifecycleService.ScanOutcome finalOutcome = service.scan(attachment.getId());

        assertThat(finalOutcome).isEqualTo(AttachmentLifecycleService.ScanOutcome.REJECTED);
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.REJECTED);
        assertThat(attachment.getScanAttempts()).isEqualTo(3);
        assertThat(attachment.getLastError()).contains("scanner offline");
        verify(outboxPublisher, times(1)).publish(eq(attachment.getId().toString()),
                eq(AttachmentEvents.FILE_REJECTED), any(AttachmentEvents.FileRejected.class));
    }

    @Test
    void scanningAnAlreadyDecidedFileIsIgnored() {
        Attachment attachment = attachment(FileStatus.READY, 0);
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));

        assertThat(service.scan(attachment.getId()))
                .isEqualTo(AttachmentLifecycleService.ScanOutcome.IGNORED);

        verify(malwareScanner, never()).scan(any());
        verify(attachmentRepository, never()).save(any());
        verify(outboxPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void aChatSyncFailureIsRetriedInsteadOfMarkingTheFileSynced() {
        Attachment attachment = attachment(FileStatus.READY, 0);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        doThrow(new DependencyUnavailableException("CHAT_SYNC_FAILED", "chat down"))
                .doNothing()
                .when(chatServiceClient).updateMessageAttachments(any(), anyList());

        assertThat(service.syncReady(attachment.getId())).isFalse();
        assertThat(attachment.getChatSyncedAt()).isNull();

        assertThat(service.syncReady(attachment.getId())).isTrue();
        assertThat(attachment.getChatSyncedAt()).isNotNull();
        verify(chatServiceClient, times(2)).updateMessageAttachments("message-1",
                List.of(attachment.getId().toString()));
    }

    @Test
    void deletingAMessageRemovesLiveAndQuarantinedObjectsAndMetadata() {
        Attachment first = attachment(FileStatus.READY, 0);
        Attachment second = attachment(FileStatus.REJECTED, 0);
        when(attachmentRepository.findByMessageIdOrderByCreatedAtAsc("message-1"))
                .thenReturn(List.of(first, second));

        assertThat(service.deleteForMessage("message-1")).isEqualTo(2);

        verify(objectStorage).delete(first.getObjectKey());
        verify(objectStorage).deleteFromQuarantine(first.getObjectKey());
        verify(objectStorage).delete(second.getObjectKey());
        verify(objectStorage).deleteFromQuarantine(second.getObjectKey());
        verify(attachmentRepository).deleteAll(List.of(first, second));
    }

    @Test
    void repeatedDeletionEventsAreIdempotent() {
        Attachment attachment = attachment(FileStatus.READY, 0);
        when(attachmentRepository.findByMessageIdOrderByCreatedAtAsc("message-1"))
                .thenReturn(List.of(attachment))
                .thenReturn(List.of());

        service.deleteForMessage("message-1");
        int secondRun = service.deleteForMessage("message-1");

        assertThat(secondRun).isZero();
        verify(objectStorage, times(1)).delete(attachment.getObjectKey());
        verify(objectStorage, times(1)).deleteFromQuarantine(attachment.getObjectKey());
        verify(attachmentRepository, times(1)).deleteAll(anyList());
    }

    @Test
    void aStorageFailureDuringCleanupKeepsTheMetadataForARetry() {
        Attachment attachment = attachment(FileStatus.READY, 0);
        when(attachmentRepository.findByMessageIdOrderByCreatedAtAsc("message-1"))
                .thenReturn(List.of(attachment));
        doThrow(new ObjectStorageException("minio down", null))
                .when(objectStorage).delete(attachment.getObjectKey());

        assertThatThrownBy(() -> service.deleteForMessage("message-1"))
                .isInstanceOf(ObjectStorageException.class);

        verify(attachmentRepository, never()).deleteAll(anyList());
    }

    private Attachment attachment(FileStatus status, int scanAttempts) {
        UUID id = UUID.randomUUID();
        return Attachment.builder()
                .id(id)
                .userId(USER)
                .roomId("room-1")
                .messageId("message-1")
                .fileName("photo.png")
                .fileType(SupportedFileType.PNG)
                .kind(AttachmentKind.IMAGE)
                .fileSizeBytes(2048)
                .objectKey("attachments/" + id)
                .status(status)
                .uploadedAt(Instant.now())
                .scannedAt(status == FileStatus.PENDING_SCAN ? null : Instant.now())
                .scanAttempts(scanAttempts)
                .createdAt(Instant.now())
                .build();
    }
}
