package com.ziyadsamhaoui.messagingattachmentservice.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ziyadsamhaoui.messagingattachmentservice.model.ProcessedEvent;
import com.ziyadsamhaoui.messagingattachmentservice.repository.ProcessedEventRepository;
import com.ziyadsamhaoui.messagingattachmentservice.service.AttachmentLifecycleService;
import com.ziyadsamhaoui.messagingattachmentservice.storage.ObjectStorageException;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MessageDeletedConsumerTest {

    private static final String TOPIC = "badrlink.chat.message.v1";

    private AttachmentLifecycleService lifecycleService;
    private ProcessedEventRepository processedEventRepository;
    private MessageDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(AttachmentLifecycleService.class);
        processedEventRepository = mock(ProcessedEventRepository.class);
        consumer = new MessageDeletedConsumer(lifecycleService, processedEventRepository, new ObjectMapper());
    }

    @Test
    void aMessageDeletedEventRemovesObjectsAndRecordsTheEventId() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        consumer.onMessage(record(envelope(eventId, "MESSAGE_DELETED", "\"messageId\":\"m-1\"")));

        verify(lifecycleService).deleteForMessage("m-1");
        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo(eventId);
        assertThat(saved.getValue().getEventType()).isEqualTo("MESSAGE_DELETED");
        assertThat(saved.getValue().getProcessedAt()).isNotNull();
    }

    @Test
    void aRedeliveredEventIsSkippedWithoutDeletingAgain() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        consumer.onMessage(record(envelope(eventId, "MESSAGE_DELETED", "\"messageId\":\"m-1\"")));

        verify(lifecycleService, never()).deleteForMessage(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void eventsFromTheSameTopicThatAreNotDeletionsAreIgnored() {
        UUID eventId = UUID.randomUUID();

        consumer.onMessage(record(envelope(eventId, "MESSAGE_ATTACHMENTS_UPDATED", "\"messageId\":\"m-1\"")));

        verify(lifecycleService, never()).deleteForMessage(any());
        verify(processedEventRepository, never()).existsById(any(UUID.class));
    }

    @Test
    void anEventWithoutAMessageIdIsDiscarded() {
        UUID eventId = UUID.randomUUID();

        consumer.onMessage(record(envelope(eventId, "MESSAGE_DELETED", "\"roomId\":\"r-1\"")));

        verify(lifecycleService, never()).deleteForMessage(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void anUnreadableEventIsDiscarded() {
        consumer.onMessage(record("not json"));

        verify(lifecycleService, never()).deleteForMessage(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void aStorageFailureLeavesTheEventUnrecordedSoKafkaCanRetryIt() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(lifecycleService.deleteForMessage("m-1"))
                .thenThrow(new ObjectStorageException("minio down", new RuntimeException("down")));

        assertThatThrownBy(() -> consumer.onMessage(
                record(envelope(eventId, "MESSAGE_DELETED", "\"messageId\":\"m-1\""))))
                .isInstanceOf(ObjectStorageException.class);

        verify(processedEventRepository, never()).save(any());
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, "key", value);
    }

    private static String envelope(UUID eventId, String eventType, String payloadFields) {
        return """
                {
                  "eventId": "%s",
                  "eventType": "%s",
                  "eventVersion": 1,
                  "producer": "messaging-chat-service",
                  "aggregateType": "Message",
                  "payload": {%s}
                }
                """.formatted(eventId, eventType, payloadFields);
    }
}
