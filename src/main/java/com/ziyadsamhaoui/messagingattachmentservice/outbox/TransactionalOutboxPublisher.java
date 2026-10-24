package com.ziyadsamhaoui.messagingattachmentservice.outbox;

import com.ziyadsamhaoui.messagingattachmentservice.observability.TraceContextCarrier;
import com.ziyadsamhaoui.messagingattachmentservice.web.CorrelationContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TransactionalOutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateId, String eventType, Object payload) {
        outboxEventRepository.save(OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(AttachmentEvents.AGGREGATE_TYPE)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .eventVersion(EventEnvelope.VERSION_1)
                .payload(objectMapper.writeValueAsString(payload))
                .correlationId(CorrelationContext.current())
                .traceParent(TraceContextCarrier.currentTraceParent())
                .build());
    }
}
