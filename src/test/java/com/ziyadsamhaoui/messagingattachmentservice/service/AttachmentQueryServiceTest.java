package com.ziyadsamhaoui.messagingattachmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ziyadsamhaoui.messagingattachmentservice.client.ChatServiceClient;
import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentPage;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.dto.DownloadUrlResponse;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ConflictException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ForbiddenOperationException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.InvalidRequestException;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.AttachmentKind;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.SupportedFileType;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachmentQueryServiceTest {

    private static final String ROOM = "room-1";
    private static final String AUTHORIZATION = "Bearer caller-token";

    private AttachmentRepository attachmentRepository;
    private ObjectStorage objectStorage;
    private ChatServiceClient chatServiceClient;
    private AttachmentProperties properties;
    private AttachmentCursorCodec cursorCodec;
    private AttachmentQueryService service;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(AttachmentRepository.class);
        objectStorage = mock(ObjectStorage.class);
        chatServiceClient = mock(ChatServiceClient.class);
        properties = AttachmentUploadServiceTest.properties(3);
        cursorCodec = new AttachmentCursorCodec();
        service = new AttachmentQueryService(attachmentRepository, objectStorage, chatServiceClient, properties,
                cursorCodec);
        when(objectStorage.presignedGetUrl(anyString(), any())).thenAnswer(invocation ->
                "https://get/" + invocation.getArgument(0));
    }

    @Test
    void galleryIsAuthorizedWithChatAndReturnsANextCursorAndTotalCountOnTheFirstPage() {
        Attachment newest = ready("a");
        Attachment older = ready("b");
        Attachment unused = ready("c");
        when(attachmentRepository.findReadyPage(eq(ROOM), eq(null), eq(null), eq(3)))
                .thenReturn(List.of(newest, older, unused));
        when(attachmentRepository.countByRoomIdAndStatus(ROOM, FileStatus.READY)).thenReturn(7L);

        AttachmentPage page = service.gallery(ROOM, null, 2, AUTHORIZATION);

        verify(chatServiceClient).requireRoomAccess(ROOM, AUTHORIZATION);
        assertThat(page.items()).extracting(AttachmentResponse::id)
                .containsExactly(newest.getId(), older.getId());
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(cursorCodec.encode(older.getUploadedAt(), older.getId()));
        assertThat(page.totalCount()).isEqualTo(7L);
        assertThat(page.items().get(0).url()).startsWith("https://get/");
    }

    @Test
    void laterPagesDecodeTheCursorAndOmitTheTotalCount() {
        Attachment newest = ready("a");
        Attachment older = ready("b");
        String cursor = cursorCodec.encode(older.getUploadedAt(), older.getId());
        when(attachmentRepository.findReadyPage(ROOM, older.getUploadedAt(), older.getId(), 3))
                .thenReturn(List.of(older));

        AttachmentPage page = service.gallery(ROOM, cursor, 2, AUTHORIZATION);

        assertThat(page.items()).extracting(AttachmentResponse::id).containsExactly(older.getId());
        assertThat(page.hasMore()).isFalse();
        assertThat(page.totalCount()).isNull();
        verify(attachmentRepository, never()).countByRoomIdAndStatus(anyString(), any());
    }

    @Test
    void theCursorKeepsPagesStableWhenTwoFilesShareATimestamp() {
        Instant shared = Instant.parse("2026-10-18T10:00:00Z");
        Attachment firstPageLast = ready("b", shared);
        Attachment sameTimestampOnNextPage = ready("a", shared);
        when(attachmentRepository.findReadyPage(eq(ROOM), eq(null), eq(null), eq(3)))
                .thenReturn(List.of(ready("c", shared), firstPageLast, sameTimestampOnNextPage));

        AttachmentPage first = service.gallery(ROOM, null, 2, AUTHORIZATION);

        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor())
                .isEqualTo(cursorCodec.encode(shared, firstPageLast.getId()));

        when(attachmentRepository.findReadyPage(eq(ROOM), eq(shared), eq(firstPageLast.getId()), eq(3)))
                .thenReturn(List.of(sameTimestampOnNextPage));
        AttachmentPage second = service.gallery(ROOM, first.nextCursor(), 2, AUTHORIZATION);

        assertThat(second.items()).extracting(AttachmentResponse::id)
                .containsExactly(sameTimestampOnNextPage.getId());
        verify(attachmentRepository).findReadyPage(ROOM, shared, firstPageLast.getId(), 3);
    }

    @Test
    void aMalformedCursorIsRejectedBeforeTheGalleryQueryRuns() {
        assertThatThrownBy(() -> service.gallery(ROOM, "%%%not-base64%%%", 10, AUTHORIZATION))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVALID_CURSOR"));

        verify(attachmentRepository, never()).findReadyPage(anyString(), any(), any(), anyInt());
    }

    @Test
    void galleryIsForbiddenWhenChatDeniesTheRoom() {
        doThrow(new ForbiddenOperationException("ROOM_ACCESS_DENIED", "not a participant"))
                .when(chatServiceClient).requireRoomAccess(ROOM, AUTHORIZATION);

        assertThatThrownBy(() -> service.gallery(ROOM, null, 10, AUTHORIZATION))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(attachmentRepository, never()).findReadyPage(anyString(), any(), any(), anyInt());
    }

    @Test
    void thePageSizeIsCappedByConfigurationAndRejectsZero() {
        when(attachmentRepository.findReadyPage(eq(ROOM), eq(null), eq(null), eq(101)))
                .thenReturn(List.of());
        when(attachmentRepository.countByRoomIdAndStatus(ROOM, FileStatus.READY)).thenReturn(0L);

        service.gallery(ROOM, null, 500, AUTHORIZATION);
        verify(attachmentRepository).findReadyPage(ROOM, null, null, 101);

        assertThatThrownBy(() -> service.gallery(ROOM, null, 0, AUTHORIZATION))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INVALID_LIMIT"));
    }

    @Test
    void metadataIsAuthorizedAgainstTheStoredRoom() {
        Attachment attachment = ready("a");
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        AttachmentResponse response = service.metadata(attachment.getId(), AUTHORIZATION);

        verify(chatServiceClient).requireRoomAccess(ROOM, AUTHORIZATION);
        assertThat(response.id()).isEqualTo(attachment.getId());
        assertThat(response.url()).startsWith("https://get/");
    }

    @Test
    void aPendingAttachmentCannotBeDownloaded() {
        Attachment attachment = ready("a");
        attachment.setStatus(FileStatus.PENDING_SCAN);
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> service.download(attachment.getId(), AUTHORIZATION))
                .isInstanceOfSatisfying(ConflictException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ATTACHMENT_NOT_READY"));

        verify(objectStorage, never()).presignedGetUrl(anyString(), any());
    }

    @Test
    void aReadyAttachmentGetsAShortLivedDownloadUrl() {
        Attachment attachment = ready("a");
        when(attachmentRepository.findById(attachment.getId())).thenReturn(Optional.of(attachment));

        DownloadUrlResponse response = service.download(attachment.getId(), AUTHORIZATION);

        assertThat(response.fileName()).isEqualTo("photo-a.png");
        assertThat(response.url()).isEqualTo("https://get/" + attachment.getObjectKey());
        assertThat(response.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void lookupAuthorizesEachRoomOnce() {
        Attachment first = ready("a");
        Attachment second = ready("b");
        when(attachmentRepository.findAllById(List.of(first.getId(), second.getId())))
                .thenReturn(List.of(first, second));

        List<AttachmentResponse> responses = service.lookup(List.of(first.getId(), second.getId()),
                AUTHORIZATION);

        assertThat(responses).hasSize(2);
        verify(chatServiceClient).requireRoomAccess(ROOM, AUTHORIZATION);
    }

    private Attachment ready(String suffix) {
        return ready(suffix, Instant.parse("2026-10-18T10:00:00Z")
                .plusMillis(Math.abs((long) suffix.hashCode()) % 1000));
    }

    private Attachment ready(String suffix, Instant uploadedAt) {
        UUID id = UUID.nameUUIDFromBytes(suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Attachment.builder()
                .id(id)
                .userId(UUID.randomUUID())
                .roomId(ROOM)
                .messageId("message-" + suffix)
                .fileName("photo-" + suffix + ".png")
                .fileType(SupportedFileType.PNG)
                .kind(AttachmentKind.IMAGE)
                .fileSizeBytes(2048)
                .objectKey("attachments/" + id)
                .status(FileStatus.READY)
                .malwareStatus(com.ziyadsamhaoui.messagingattachmentservice.model.enums.MalwareStatus.SAFE)
                .moderationStatus(com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus.SFW)
                .uploadedAt(uploadedAt)
                .scannedAt(uploadedAt)
                .createdAt(uploadedAt)
                .build();
    }
}
