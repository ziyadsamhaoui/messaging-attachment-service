package com.ziyadsamhaoui.messagingattachmentservice.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends AttachmentServiceException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
