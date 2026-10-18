package com.ziyadsamhaoui.messagingattachmentservice.controller;

import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentPage;
import com.ziyadsamhaoui.messagingattachmentservice.dto.AttachmentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.dto.CreateUploadIntentRequest;
import com.ziyadsamhaoui.messagingattachmentservice.dto.DownloadUrlResponse;
import com.ziyadsamhaoui.messagingattachmentservice.dto.LookupAttachmentsRequest;
import com.ziyadsamhaoui.messagingattachmentservice.dto.UploadIntentResponse;
import com.ziyadsamhaoui.messagingattachmentservice.security.CurrentUserProvider;
import com.ziyadsamhaoui.messagingattachmentservice.service.AttachmentQueryService;
import com.ziyadsamhaoui.messagingattachmentservice.service.AttachmentUploadService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/attachments")
public class AttachmentController {

    private final AttachmentUploadService uploadService;
    private final AttachmentQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    public AttachmentController(AttachmentUploadService uploadService, AttachmentQueryService queryService,
            CurrentUserProvider currentUserProvider) {
        this.uploadService = uploadService;
        this.queryService = queryService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadIntentResponse createIntent(@Valid @RequestBody CreateUploadIntentRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        return uploadService.createIntent(currentUserProvider.requireUserId(), request, authorization);
    }

    @PostMapping("/{attachmentId}/confirm")
    public AttachmentResponse confirm(@PathVariable UUID attachmentId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        return uploadService.confirmUpload(currentUserProvider.requireUserId(), attachmentId, authorization);
    }

    @GetMapping("/{attachmentId}")
    public AttachmentResponse metadata(@PathVariable UUID attachmentId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        return queryService.metadata(attachmentId, authorization);
    }

    @GetMapping("/{attachmentId}/download")
    public DownloadUrlResponse download(@PathVariable UUID attachmentId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        return queryService.download(attachmentId, authorization);
    }

    @PostMapping("/lookup")
    public List<AttachmentResponse> lookup(@Valid @RequestBody LookupAttachmentsRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        return queryService.lookup(request.ids(), authorization);
    }

    @GetMapping("/rooms/{roomId}")
    public AttachmentPage gallery(@PathVariable String roomId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        return queryService.gallery(roomId, cursor, limit, authorization);
    }
}
