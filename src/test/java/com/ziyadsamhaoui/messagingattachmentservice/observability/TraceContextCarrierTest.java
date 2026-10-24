package com.ziyadsamhaoui.messagingattachmentservice.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextCarrierTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void capturesAndRestoresTheSameTrace() {
        Context context = Context.root().with(Span.wrap(
                SpanContext.create(TRACE_ID, SPAN_ID, TraceFlags.getSampled(), TraceState.getDefault())));

        String traceParent = TraceContextCarrier.traceParent(context);
        Context restored = TraceContextCarrier.extract(traceParent);

        assertThat(traceParent).isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(restored).isNotNull();
        assertThat(Span.fromContext(restored).getSpanContext().getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void scheduledJobsHaveNoTraceContextToPropagate() {
        assertThat(TraceContextCarrier.traceParent(Context.root())).isNull();
        assertThat(TraceContextCarrier.currentTraceParent()).isNull();
    }

    @Test
    void invalidStoredValuesProduceNoContext() {
        assertThat(TraceContextCarrier.extract(null)).isNull();
        assertThat(TraceContextCarrier.extract("")).isNull();
        assertThat(TraceContextCarrier.extract("garbage")).isNull();
    }
}
