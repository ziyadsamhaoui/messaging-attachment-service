package com.ziyadsamhaoui.messagingattachmentservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "badrlink.chat-service")
public record ChatServiceProperties(
        String baseUrl,
        String roomPath,
        String attachmentsPath,
        String internalTokenHeader,
        String internalToken,
        Duration connectTimeout,
        Duration readTimeout) {
}
