package com.ziyadsamhaoui.messagingattachmentservice.exception;

import org.springframework.http.HttpStatus;

public class UnauthenticatedRequestException extends AttachmentServiceException {

    public UnauthenticatedRequestException(String message) {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message);
    }
}
