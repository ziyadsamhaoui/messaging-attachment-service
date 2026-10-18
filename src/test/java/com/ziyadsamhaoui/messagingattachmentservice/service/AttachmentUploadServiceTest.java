package com.ziyadsamhaoui.messagingattachmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ziyadsamhaoui.messagingattachmentservice.client.ChatServiceClient;
import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.dto.CreateUploadIntentRequest;
import com.ziyadsamhaoui.messagingattachmentservice.dto.UploadIntentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ConflictException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ForbiddenOperationException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.InvalidRequestException;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.AttachmentKind;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.SupportedFileType;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.AttachmentEvents;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.TransactionalOutboxPublisher;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorage;
import com.ziyadsamhaoui.messagingattachmentservice.storage.StoredObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AttachmentUploadServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String AUTHORIZATION = "Bearer caller-token";

    private AttachmentRepository attachmentRepository;
    private ObjectStorage objectStorage;
    private ChatServiceClient chatServiceClient;
    private TransactionalOutboxPublisher outboxPublisher;
    private AttachmentProperties properties;
    private AttachmentUploadService service;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(AttachmentRepository.class);
        objectStorage = mock(ObjectStorage.class);
        chatServiceClient = mock(ChatServiceClient.class);
        outboxPublisher = mock(TransactionalOutboxPublisher.class);
        properties = properties(3);
        service = new AttachmentUploadService(attachmentRepository, objectStorage, chatServiceClient, properties,
                outboxPublisher, new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void intentFromANonParticipantIsForbiddenAndLeavesNoAttachment() {
        doThrow(new ForbiddenOperationException("ROOM_ACCESS_DENIED", "not a participant"))
                .when(chatServiceClient).requireRoomAccess("room-1", AUTHORIZATION);

        assertThatThrownBy(() -> service.createIntent(USER, request("audio/webm;codecs=opus", 1024), AUTHORIZATION))
                .isInstanceOfSatisfying(ForbiddenOperationException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getCode()).isEqualTo("ROOM_ACCESS_DENIED");
                });

        verify(attachmentRepository, never()).save(any());
        verify(objectStorage, never()).presignedPutUrl(any(), any());
    }

    @Test
    void intentStoresAnObjectKeyThatCannotBeInfluencedByTheFileName() {
        when(objectStorage.presignedPutUrl(any(), eq(properties.uploadUrlTtl()))).thenReturn("https://put");
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UploadIntentResponse response = service.createIntent(USER,
                request("image/png", 2048, "../../private/photo.png"), AUTHORIZATION);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepository).save(saved.capture());
        assertThat(saved.getValue().getObjectKey()).isEqualTo("attachments/" + saved.getValue().getId());
        assertThat(saved.getValue().getObjectKey()).doesNotContain("photo");
        assertThat(saved.getValue().getFileName()).isEqualTo(".._.._private_photo.png");
        assertThat(saved.getValue().getStatus()).isEqualTo(FileStatus.PENDING_UPLOAD);
        assertThat(saved.getValue().getKind()).isEqualTo(AttachmentKind.IMAGE);
        assertThat(response.uploadUrl()).isEqualTo("https://put");
        assertThat(response.status()).isEqualTo("PENDING_UPLOAD");
    }

    @Test
    void intentAcceptsBrowserWebmAndOggRecordings() {
        when(objectStorage.presignedPutUrl(any(), any())).thenReturn("https://put");
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createIntent(USER, request("audio/webm;codecs=opus", 4096, "voice.webm"), AUTHORIZATION);
        service.createIntent(USER, request("application/ogg", 4096, "voice.ogg"), AUTHORIZATION);

        ArgumentCaptor<Attachment> saved = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getFileType()).isEqualTo(SupportedFileType.WEBM);
        assertThat(saved.getAllValues().get(0).getKind()).isEqualTo(AttachmentKind.AUDIO);
        assertThat(saved.getAllValues().get(1).getFileType()).isEqualTo(SupportedFileType.OGG);
    }

    @Test
    void intentRejectsUnsupportedMimeTypes() {
        assertThatThrownBy(() -> service.createIntent(USER, request("application/x-msdownload", 10),
                AUTHORIZATION))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE"));
        verifyNoRepositoryWork();
    }

    @Test
    void intentRejectsFilesOverTheServerLimit() {
        assertThatThrownBy(() -> service.createIntent(USER,
                request("image/png", properties.maxFileSizeBytes() + 1), AUTHORIZATION))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("FILE_TOO_LARGE"));
        verifyNoRepositoryWork();
    }

    @Test
    void intentRejectsAFileNameThatCannotBeSafelyStored() {
        assertThatThrownBy(() -> service.createIntent(USER, request("image/png", 10, "   "), AUTHORIZATION))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVALID_FILE_NAME"));
        verifyNoRepositoryWork();
    }

    @Test
    void confirmationWithoutAnObjectInStorageIsRejectedAndDoesNotTransition() {
        Attachment attachment = attachment(FileStatus.PENDING_UPLOAD, 1024);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(objectStorage.stat(attachment.getObjectKey())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmUpload(USER, attachment.getId(), AUTHORIZATION))
                .isInstanceOfSatisfying(ConflictException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ATTACHMENT_OBJECT_MISSING"));

        assertThat(attachment.getStatus()).isEqualTo(FileStatus.PENDING_UPLOAD);
        verify(attachmentRepository, never()).save(any());
        verify(outboxPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void confirmationWithADifferentStoredSizeIsRejected() {
        Attachment attachment = attachment(FileStatus.PENDING_UPLOAD, 1024);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(objectStorage.stat(attachment.getObjectKey()))
                .thenReturn(Optional.of(new StoredObject(999, "image/png")));

        assertThatThrownBy(() -> service.confirmUpload(USER, attachment.getId(), AUTHORIZATION))
                .isInstanceOfSatisfying(ConflictException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ATTACHMENT_SIZE_MISMATCH"));

        assertThat(attachment.getStatus()).isEqualTo(FileStatus.PENDING_UPLOAD);
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void confirmationMovesTheAttachmentToScanningAndPublishesFileUploaded() {
        Attachment attachment = attachment(FileStatus.PENDING_UPLOAD, 1024);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.lockById(attachment.getId())).thenReturn(Optional.of(attachment));
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectStorage.stat(attachment.getObjectKey()))
                .thenReturn(Optional.of(new StoredObject(1024, "image/png")));

        AttachmentResponse response = service.confirmUpload(USER, attachment.getId(), AUTHORIZATION);

        assertThat(response.status()).isEqualTo("PENDING_SCAN");
        assertThat(attachment.getStatus()).isEqualTo(FileStatus.PENDING_SCAN);
        assertThat(attachment.getUploadedAt()).isNotNull();
        verify(outboxPublisher).publish(eq(attachment.getId().toString()), eq(AttachmentEvents.FILE_UPLOADED),
                any(AttachmentEvents.FileUploaded.class));
    }

    @Test
    void repeatingAConfirmationIsAConflict() {
        Attachment attachment = attachment(FileStatus.PENDING_SCAN, 1024);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> service.confirmUpload(USER, attachment.getId(), AUTHORIZATION))
                .isInstanceOfSatisfying(ConflictException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ATTACHMENT_NOT_PENDING_UPLOAD"));

        verify(objectStorage, never()).stat(any());
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void onlyTheUploaderCanConfirmTheirIntent() {
        Attachment attachment = attachment(FileStatus.PENDING_UPLOAD, 1024);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> service.confirmUpload(UUID.randomUUID(), attachment.getId(), AUTHORIZATION))
                .isInstanceOfSatisfying(ForbiddenOperationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ATTACHMENT_OWNER_REQUIRED"));

        verify(chatServiceClient, never()).requireRoomAccess(any(), any());
    }

    private void verifyNoRepositoryWork() {
        verify(chatServiceClient, never()).requireRoomAccess(any(), any());
        verify(attachmentRepository, never()).save(any());
    }

    private CreateUploadIntentRequest request(String fileType, long size) {
        return request(fileType, size, "photo.png");
    }

    private CreateUploadIntentRequest request(String fileType, long size, String fileName) {
        return new CreateUploadIntentRequest("room-1", "message-1", fileName, fileType, size);
    }

    private Attachment attachment(FileStatus status, long size) {
        UUID id = UUID.randomUUID();
        return Attachment.builder()
                .id(id)
                .userId(USER)
                .roomId("room-1")
                .messageId("message-1")
                .fileName("photo.png")
                .fileType(SupportedFileType.PNG)
                .kind(AttachmentKind.IMAGE)
                .fileSizeBytes(size)
                .objectKey("attachments/" + id)
                .status(status)
                .uploadedAt(status == FileStatus.PENDING_UPLOAD ? null : Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    static AttachmentProperties properties(int maxScanAttempts) {
        return new AttachmentProperties(10_000_000L, 30, 100, Duration.ofMinutes(5), Duration.ofMinutes(10),
                new AttachmentProperties.Scan(Duration.ofSeconds(5), 20, maxScanAttempts),
                new AttachmentProperties.Minio("http://localhost:9000", "key", "secret", "attachments",
                        "quarantine", true));
    }
}
