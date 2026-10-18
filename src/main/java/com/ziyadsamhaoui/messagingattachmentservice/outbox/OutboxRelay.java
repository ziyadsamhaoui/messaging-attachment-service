package com.ziyadsamhaoui.messagingattachmentservice.outbox;

import static com.ziyadsamhaoui.messagingattachmentservice.outbox.OutboxStatus.FAILED_DEAD_LETTER;
import static com.ziyadsamhaoui.messagingattachmentservice.outbox.OutboxStatus.PENDING;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int maxAttempts;

    @Scheduled(fixedDelayString = "${badrlink.kafka.outbox.relay-interval:500ms}")
    public void publishPending() {
        List<OutboxEvent> batch = fetchPendingBatch();
        for (OutboxEvent event : batch) {
            if (!publishOne(event)) {
                return;
            }
        }
    }

    private List<OutboxEvent> fetchPendingBatch() {
        List<OutboxEvent> batch = transactionTemplate.execute(status ->
                outboxEventRepository.lockPendingBatch(batchSize));
        return batch == null ? List.of() : batch;
    }

    boolean publishOne(OutboxEvent event) {
        try {
            send(event);
            transactionTemplate.executeWithoutResult(status ->
                    outboxEventRepository.markProcessed(event.getId()));
            log.debug("outbox event {} published to {}", event.getId(), AttachmentEvents.TOPIC);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(event, ex);
            return false;
        } catch (Exception ex) {
            recordFailure(event, ex);
            return true;
        }
    }

    private void send(OutboxEvent event) throws Exception {
        String envelopeJson = objectMapper.writeValueAsString(toEnvelope(event));
        kafkaTemplate.send(AttachmentEvents.TOPIC, event.getAggregateId(), envelopeJson).get(10, TimeUnit.SECONDS);
    }

    private void recordFailure(OutboxEvent event, Exception ex) {
        int attempts = event.getAttempts() + 1;
        OutboxStatus status = attempts >= maxAttempts ? FAILED_DEAD_LETTER : PENDING;
        Instant nextRetryAt = Instant.now().plusSeconds(1L << attempts);
        String error = truncate(ex.getMessage());
        transactionTemplate.executeWithoutResult(ignored ->
                outboxEventRepository.recordFailure(event.getId(), attempts, error, status.name(), nextRetryAt));
        log.warn("outbox event {} failed on attempt {} with status {}: {}",
                event.getId(), attempts, status, error);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private EventEnvelope toEnvelope(OutboxEvent event) {
        return new EventEnvelope(
                event.getId(),
                event.getEventType(),
                event.getEventVersion(),
                event.getCreatedAt(),
                EventEnvelope.PRODUCER,
                event.getCorrelationId(),
                event.getAggregateId(),
                objectMapper.readTree(event.getPayload()));
    }
}
