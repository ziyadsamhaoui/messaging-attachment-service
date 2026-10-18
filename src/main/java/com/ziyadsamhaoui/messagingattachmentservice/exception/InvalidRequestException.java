package com.ziyadsamhaoui.messagingattachmentservice.exception;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends AttachmentServiceException {

    public InvalidRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
