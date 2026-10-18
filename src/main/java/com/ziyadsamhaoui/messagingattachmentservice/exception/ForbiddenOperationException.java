package com.ziyadsamhaoui.messagingattachmentservice.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenOperationException extends AttachmentServiceException {

    public ForbiddenOperationException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
