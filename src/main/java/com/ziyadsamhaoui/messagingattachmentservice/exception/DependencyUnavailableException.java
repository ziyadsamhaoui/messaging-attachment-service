package com.ziyadsamhaoui.messagingattachmentservice.exception;

import org.springframework.http.HttpStatus;

public class DependencyUnavailableException extends AttachmentServiceException {

    public DependencyUnavailableException(String code, String message) {
        super(HttpStatus.BAD_GATEWAY, code, message);
    }
}
