package com.ziyadsamhaoui.messagingattachmentservice.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTraceContextTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        relay = new OutboxRelay(repository, kafkaTemplate, new ObjectMapper(),
                new TransactionTemplate(transactionManager), 50, 5);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private OutboxEvent event(String traceParent) {
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(AttachmentEvents.AGGREGATE_TYPE)
                .aggregateId(UUID.randomUUID().toString())
                .eventType(AttachmentEvents.FILE_READY)
                .eventVersion(1)
                .payload("{\"attachmentId\":\"x\"}")
                .correlationId("corr-1")
                .traceParent(traceParent)
                .createdAt(Instant.now())
                .nextRetryAt(Instant.now())
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedTraceParentIsReplayedIntoTheKafkaRecord() {
        OutboxEvent event = event(TRACE_PARENT);
        when(repository.lockPendingBatch(50)).thenReturn(List.of(event));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> record =
                (ArgumentCaptor<ProducerRecord<String, String>>) (ArgumentCaptor<?>) ArgumentCaptor
                        .forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(record.getValue().topic()).isEqualTo(AttachmentEvents.TOPIC);
        assertThat(record.getValue().key()).isEqualTo(event.getAggregateId());
        assertThat(record.getValue().headers().lastHeader("traceparent")).isNotNull();
        assertThat(new String(record.getValue().headers().lastHeader("traceparent").value()))
                .isEqualTo(TRACE_PARENT);
        assertThat(record.getValue().value()).contains("\"correlationId\":\"corr-1\"");
        verify(repository).markProcessed(event.getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduledRowsWithoutTraceContextPublishWithoutTheHeader() {
        OutboxEvent event = event(null);
        when(repository.lockPendingBatch(50)).thenReturn(List.of(event));

        relay.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> record =
                (ArgumentCaptor<ProducerRecord<String, String>>) (ArgumentCaptor<?>) ArgumentCaptor
                        .forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(record.getValue().headers().lastHeader("traceparent")).isNull();
    }
}
