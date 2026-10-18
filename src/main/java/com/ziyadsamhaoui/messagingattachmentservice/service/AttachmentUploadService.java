package com.ziyadsamhaoui.messagingattachmentservice.service;

import com.ziyadsamhaoui.messagingattachmentservice.client.ChatServiceClient;
import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.dto.CreateUploadIntentRequest;
import com.ziyadsamhaoui.messagingattachmentservice.dto.UploadIntentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ConflictException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.DependencyUnavailableException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ForbiddenOperationException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.InvalidRequestException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ResourceNotFoundException;
import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.AttachmentKind;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.SupportedFileType;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.AttachmentEvents;
import com.ziyadsamhaoui.messagingattachmentservice.outbox.TransactionalOutboxPublisher;
import com.ziyadsamhaoui.messagingattachmentservice.repository.AttachmentRepository;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorage;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorageException;
import com.ziyadsamhaoui.messagingattachmentservice.storage.StoredObject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AttachmentUploadService {

    private static final Pattern UNSAFE_FILE_NAME = Pattern.compile("[\\\\/\\p{Cntrl}]");

    private final AttachmentRepository attachmentRepository;
    private final ObjectStorage objectStorage;
    private final ChatServiceClient chatServiceClient;
    private final AttachmentProperties properties;
    private final TransactionalOutboxPublisher outboxPublisher;
    private final TransactionTemplate transactionTemplate;

    public AttachmentUploadService(AttachmentRepository attachmentRepository, ObjectStorage objectStorage,
            ChatServiceClient chatServiceClient, AttachmentProperties properties,
            TransactionalOutboxPublisher outboxPublisher, TransactionTemplate transactionTemplate) {
        this.attachmentRepository = attachmentRepository;
        this.objectStorage = objectStorage;
        this.chatServiceClient = chatServiceClient;
        this.properties = properties;
        this.outboxPublisher = outboxPublisher;
        this.transactionTemplate = transactionTemplate;
    }

    public UploadIntentResponse createIntent(UUID userId, CreateUploadIntentRequest request, String authorization) {
        SupportedFileType fileType = SupportedFileType.fromMimeType(request.fileType())
                .orElseThrow(() -> new InvalidRequestException("UNSUPPORTED_FILE_TYPE",
                        "This file type is not supported"));
        String fileName = sanitizeFileName(request.fileName());

        if (request.fileSizeBytes() <= 0) {
            throw new InvalidRequestException("INVALID_FILE_SIZE", "fileSizeBytes must be positive");
        }
        if (request.fileSizeBytes() > properties.maxFileSizeBytes()) {
            throw new InvalidRequestException("FILE_TOO_LARGE",
                    "Files may not exceed " + properties.maxFileSizeBytes() + " bytes");
        }

        chatServiceClient.requireRoomAccess(request.roomId(), authorization);

        UUID attachmentId = UUID.randomUUID();
        String objectKey = objectKeyFor(attachmentId);

        String uploadUrl;
        try {
            uploadUrl = objectStorage.presignedPutUrl(objectKey, properties.uploadUrlTtl());
        } catch (ObjectStorageException exception) {
            throw new DependencyUnavailableException("OBJECT_STORAGE_UNAVAILABLE",
                    "Object storage could not prepare the upload");
        }

        Attachment attachment = Attachment.builder()
                .id(attachmentId)
                .userId(userId)
                .roomId(request.roomId())
                .messageId(request.messageId())
                .fileName(fileName)
                .fileType(fileType)
                .kind(AttachmentKind.fromMimeType(request.fileType()))
                .fileSizeBytes(request.fileSizeBytes())
                .objectKey(objectKey)
                .status(FileStatus.PENDING_UPLOAD)
                .createdAt(Instant.now())
                .build();

        transactionTemplate.executeWithoutResult(status -> attachmentRepository.save(attachment));

        return new UploadIntentResponse(attachment.getId(), uploadUrl,
                Instant.now().plus(properties.uploadUrlTtl()), attachment.getStatus().name(),
                attachment.getFileName(), attachment.getFileType().mimeType(),
                attachment.getFileSizeBytes());
    }

    public AttachmentResponse confirmUpload(UUID userId, UUID attachmentId, String authorization) {
        Attachment attachment = requireAttachment(attachmentId);
        if (!attachment.getUserId().equals(userId)) {
            throw new ForbiddenOperationException("ATTACHMENT_OWNER_REQUIRED",
                    "Only the uploader can confirm this upload");
        }
        chatServiceClient.requireRoomAccess(attachment.getRoomId(), authorization);

        if (attachment.getStatus() != FileStatus.PENDING_UPLOAD) {
            throw new ConflictException("ATTACHMENT_NOT_PENDING_UPLOAD",
                    "This upload has already been confirmed");
        }

        Optional<StoredObject> stored;
        try {
            stored = objectStorage.stat(attachment.getObjectKey());
        } catch (ObjectStorageException exception) {
            throw new DependencyUnavailableException("OBJECT_STORAGE_UNAVAILABLE",
                    "Object storage could not verify the upload");
        }

        if (stored.isEmpty()) {
            throw new ConflictException("ATTACHMENT_OBJECT_MISSING",
                    "The file was not found in object storage");
        }
        if (stored.get().size() != attachment.getFileSizeBytes()) {
            throw new ConflictException("ATTACHMENT_SIZE_MISMATCH",
                    "The uploaded file size does not match the declared size");
        }

        Attachment confirmed = transactionTemplate.execute(status -> {
            Attachment locked = attachmentRepository.lockById(attachmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("ATTACHMENT_NOT_FOUND",
                            "Attachment not found"));
            if (locked.getStatus() != FileStatus.PENDING_UPLOAD) {
                throw new ConflictException("ATTACHMENT_NOT_PENDING_UPLOAD",
                        "This upload has already been confirmed");
            }
            locked.setStatus(FileStatus.PENDING_SCAN);
            locked.setUploadedAt(Instant.now());
            attachmentRepository.save(locked);
            outboxPublisher.publish(locked.getId().toString(), AttachmentEvents.FILE_UPLOADED,
                    new AttachmentEvents.FileUploaded(locked.getId().toString(), locked.getRoomId(),
                            locked.getMessageId(), locked.getUserId().toString(), locked.getFileName(),
                            locked.getFileType().mimeType(), locked.getFileSizeBytes(), locked.getUploadedAt()));
            return locked;
        });

        if (confirmed == null) {
            throw new ConflictException("ATTACHMENT_NOT_PENDING_UPLOAD",
                    "This upload has already been confirmed");
        }
        return AttachmentResponse.of(confirmed, null, null);
    }

    private Attachment requireAttachment(UUID attachmentId) {
        return attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ATTACHMENT_NOT_FOUND",
                        "Attachment not found"));
    }

    static String objectKeyFor(UUID attachmentId) {
        return "attachments/" + attachmentId;
    }

    static String sanitizeFileName(String declared) {
        String cleaned = UNSAFE_FILE_NAME.matcher(declared == null ? "" : declared).replaceAll("_").trim();
        if (cleaned.isBlank()) {
            throw new InvalidRequestException("INVALID_FILE_NAME", "fileName is not usable");
        }
        return cleaned.length() <= 255 ? cleaned : cleaned.substring(0, 255);
    }
}
