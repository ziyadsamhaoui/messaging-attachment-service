package com.ziyadsamhaoui.messagingattachmentservice.kafka;

import com.ziyadsamhaoui.messagingattachmentservice.model.ProcessedEvent;
import com.ziyadsamhaoui.messagingattachmentservice.repository.ProcessedEventRepository;
import com.ziyadsamhaoui.messagingattachmentservice.service.AttachmentLifecycleService;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "badrlink.kafka", name = "enabled", havingValue = "true")
public class MessageDeletedConsumer {

    public static final String TOPIC = "badrlink.chat.message.v1";
    public static final String GROUP_ID = "attachment-service";

    private static final Logger log = LoggerFactory.getLogger(MessageDeletedConsumer.class);

    private final AttachmentLifecycleService lifecycleService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public MessageDeletedConsumer(AttachmentLifecycleService lifecycleService,
            ProcessedEventRepository processedEventRepository, ObjectMapper objectMapper) {
        this.lifecycleService = lifecycleService;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC, groupId = GROUP_ID)
    public void onMessage(ConsumerRecord<String, String> record) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(record.value());
        } catch (RuntimeException exception) {
            log.error("Discarding unreadable event from {}", TOPIC, exception);
            return;
        }

        JsonNode eventTypeNode = envelope.get("eventType");
        if (eventTypeNode == null || !"MESSAGE_DELETED".equals(eventTypeNode.asString())) {
            return;
        }

        UUID eventId = readEventId(envelope);
        if (eventId == null) {
            log.error("Discarding MESSAGE_DELETED without an eventId: {}", record.value());
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping already processed event {}", eventId);
            return;
        }

        JsonNode payload = envelope.get("payload");
        String messageId = payload == null ? null : text(payload, "messageId");
        if (!StringUtils.hasText(messageId)) {
            log.error("Discarding MESSAGE_DELETED without a messageId: {}", record.value());
            return;
        }

        lifecycleService.deleteForMessage(messageId);

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .eventType("MESSAGE_DELETED")
                .processedAt(Instant.now())
                .build());
    }

    private UUID readEventId(JsonNode envelope) {
        try {
            JsonNode eventId = envelope.get("eventId");
            return eventId == null || eventId.isNull() ? null : UUID.fromString(eventId.asString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
