package com.ziyadsamhaoui.messagingattachmentservice.service;

import com.ziyadsamhaoui.messagingattachmentservice.client.ChatServiceClient;
import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentPage;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.dto.DownloadUrlResponse;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ConflictException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.DependencyUnavailableException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ResourceNotFoundException;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorage;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorageException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AttachmentQueryService {

    private final AttachmentRepository attachmentRepository;
    private final ObjectStorage objectStorage;
    private final ChatServiceClient chatServiceClient;
    private final AttachmentProperties properties;
    private final AttachmentCursorCodec cursorCodec;

    public AttachmentQueryService(AttachmentRepository attachmentRepository, ObjectStorage objectStorage,
            ChatServiceClient chatServiceClient, AttachmentProperties properties,
            AttachmentCursorCodec cursorCodec) {
        this.attachmentRepository = attachmentRepository;
        this.objectStorage = objectStorage;
        this.chatServiceClient = chatServiceClient;
        this.properties = properties;
        this.cursorCodec = cursorCodec;
    }

    public AttachmentPage gallery(String roomId, String cursor, Integer limit, String authorization) {
        int pageSize = resolvePageSize(limit);
        chatServiceClient.requireRoomAccess(roomId, authorization);

        Optional<AttachmentCursorCodec.Cursor> decoded = cursorCodec.decode(cursor);
        List<Attachment> rows = attachmentRepository.findReadyPage(roomId,
                decoded.map(AttachmentCursorCodec.Cursor::uploadedAt).orElse(null),
                decoded.map(AttachmentCursorCodec.Cursor::id).orElse(null),
                pageSize + 1);

        boolean hasMore = rows.size() > pageSize;
        List<Attachment> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<AttachmentResponse> items = page.stream().map(this::withReadUrl).toList();

        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            Attachment last = page.get(page.size() - 1);
            if (last.getUploadedAt() != null) {
                nextCursor = cursorCodec.encode(last.getUploadedAt(), last.getId());
            }
        }

        Long totalCount = decoded.isEmpty()
                ? attachmentRepository.countByRoomIdAndStatus(roomId, FileStatus.READY)
                : null;

        return AttachmentPage.of(items, nextCursor, hasMore, totalCount);
    }

    public AttachmentResponse metadata(UUID attachmentId, String authorization) {
        Attachment attachment = requireAttachment(attachmentId);
        chatServiceClient.requireRoomAccess(attachment.getRoomId(), authorization);
        return withReadUrl(attachment);
    }

    public DownloadUrlResponse download(UUID attachmentId, String authorization) {
        Attachment attachment = requireAttachment(attachmentId);
        chatServiceClient.requireRoomAccess(attachment.getRoomId(), authorization);

        if (attachment.getStatus() != FileStatus.READY) {
            throw new ConflictException("ATTACHMENT_NOT_READY",
                    "This attachment is not available for download");
        }

        Instant expiresAt = Instant.now().plus(properties.downloadUrlTtl());
        return new DownloadUrlResponse(attachment.getFileName(), attachment.getFileType().mimeType(),
                attachment.getFileSizeBytes(),
                presign(attachment.getObjectKey(), properties.downloadUrlTtl()), expiresAt);
    }

    public List<AttachmentResponse> lookup(List<UUID> ids, String authorization) {
        List<Attachment> attachments = attachmentRepository.findAllById(ids);
        if (attachments.isEmpty()) {
            return List.of();
        }

        Map<String, Boolean> authorizedRooms = new LinkedHashMap<>();
        for (Attachment attachment : attachments) {
            authorizedRooms.computeIfAbsent(attachment.getRoomId(), roomId -> {
                chatServiceClient.requireRoomAccess(roomId, authorization);
                return true;
            });
        }
        return attachments.stream().map(this::withReadUrl).toList();
    }

    private AttachmentResponse withReadUrl(Attachment attachment) {
        if (attachment.getStatus() != FileStatus.READY) {
            return AttachmentResponse.of(attachment, null, null);
        }
        return AttachmentResponse.of(attachment, presign(attachment.getObjectKey(), properties.downloadUrlTtl()),
                Instant.now().plus(properties.downloadUrlTtl()));
    }

    private String presign(String objectKey, java.time.Duration ttl) {
        try {
            return objectStorage.presignedGetUrl(objectKey, ttl);
        } catch (ObjectStorageException exception) {
            throw new DependencyUnavailableException("OBJECT_STORAGE_UNAVAILABLE",
                    "Object storage could not sign the file URL");
        }
    }

    private int resolvePageSize(Integer requested) {
        if (requested == null) {
            return properties.defaultPageSize();
        }
        if (requested < 1) {
            throw new com.ziyadsamhaoui.messagingattachmentservice.exception.InvalidRequestException(
                    "INVALID_LIMIT", "limit must be greater than zero");
        }
        return Math.min(requested, properties.maxPageSize());
    }

    private Attachment requireAttachment(UUID attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ATTACHMENT_NOT_FOUND",
                        "Attachment not found"));
    }
}

