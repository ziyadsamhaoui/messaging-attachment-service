package com.ziyadsamhaoui.messagingattachmentservice.client;

import com.ziyadsamhaoui.messagingattachmentservice.config.ChatServiceProperties;
import com.ziyadsamhaoui.messagingattachmentservice.exception.DependencyUnavailableException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ForbiddenOperationException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.ResourceNotFoundException;
import com.ziyadsamhaoui.messagingattachmentservice.exception.UnauthenticatedRequestException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceClient.class);

    private final HttpClient httpClient;
    private final ChatServiceProperties properties;
    private final ObjectMapper objectMapper;

    public ChatServiceClient(ChatServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    public void requireRoomAccess(String roomId, String authorization) {
        if (!StringUtils.hasText(authorization)) {
            throw new UnauthenticatedRequestException("A bearer token is required to access a room");
        }

        HttpRequest request = HttpRequest.newBuilder(uri(properties.roomPath().replace("{roomId}", encode(roomId))))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .GET()
                .build();

        HttpResponse<Void> response = send(request, "room membership");
        int status = response.statusCode();

        if (status == 200) {
            return;
        }
        if (status == 401) {
            throw new UnauthenticatedRequestException("Chat rejected the caller's token");
        }
        if (status == 403) {
            throw new ForbiddenOperationException("ROOM_ACCESS_DENIED",
                    "You are not a participant of this room");
        }
        if (status == 404) {
            throw new ResourceNotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        throw new DependencyUnavailableException("CHAT_SERVICE_UNAVAILABLE",
                "Chat could not authorize this request");
    }

    public void updateMessageAttachments(String messageId, List<String> attachmentIds) {
        if (!StringUtils.hasText(properties.internalToken())) {
            throw new DependencyUnavailableException("CHAT_SERVICE_UNAVAILABLE",
                    "The Chat internal token is not configured");
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("attachmentIds", attachmentIds));
        } catch (RuntimeException exception) {
            throw new DependencyUnavailableException("CHAT_SYNC_FAILED", "Could not encode the Chat update");
        }

        HttpRequest request = HttpRequest.newBuilder(uri(
                        properties.attachmentsPath().replace("{messageId}", encode(messageId))))
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(properties.internalTokenHeader(), properties.internalToken())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<Void> response = send(request, "message attachment update");
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        log.warn("Chat rejected the attachment update for message {} with status {}", messageId, status);
        throw new DependencyUnavailableException("CHAT_SYNC_FAILED",
                "Chat did not accept the attachment update");
    }

    private HttpResponse<Void> send(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DependencyUnavailableException("CHAT_SERVICE_UNAVAILABLE",
                    "Chat " + operation + " was interrupted");
        } catch (Exception exception) {
            log.warn("Chat {} call failed: {}", operation, exception.getMessage());
            throw new DependencyUnavailableException("CHAT_SERVICE_UNAVAILABLE",
                    "Chat is unavailable");
        }
    }

    private URI uri(String path) {
        String base = properties.baseUrl().endsWith("/")
                ? properties.baseUrl().substring(0, properties.baseUrl().length() - 1)
                : properties.baseUrl();
        return URI.create(base + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
