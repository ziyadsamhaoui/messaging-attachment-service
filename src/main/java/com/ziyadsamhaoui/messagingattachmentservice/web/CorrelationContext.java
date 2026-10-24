package com.ziyadsamhaoui.messagingattachmentservice.web;

/**
 * Per-request correlation id, available to code that writes events outside the servlet
 * filter's immediate scope.
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void set(String correlationId) {
        CURRENT.set(correlationId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
