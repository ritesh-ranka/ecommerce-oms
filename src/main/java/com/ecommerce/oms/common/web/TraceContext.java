package com.ecommerce.oms.common.web;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Correlation-id plumbing. One trace id per request, carried in MDC so every log line —
 * including lines written by the async outbox dispatcher after the response was already
 * sent — can be tied back to the request that caused the work.
 *
 * <p>The same id is returned to the client in the {@code X-Trace-Id} response header and
 * embedded in every {@link com.ecommerce.oms.common.error.ApiError}, so a bug report
 * contains everything needed to find the corresponding log lines.
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static void putTraceId(String traceId) {
        MDC.put(TRACE_ID, traceId);
    }

    public static void putUserId(Object userId) {
        MDC.put(USER_ID, userId == null ? "-" : String.valueOf(userId));
    }

    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID);
        return traceId != null ? traceId : "-";
    }

    public static Map<String, String> snapshot() {
        return MDC.getCopyOfContextMap();
    }

    public static void restore(Map<String, String> context) {
        MDC.clear();
        if (context != null) {
            MDC.setContextMap(context);
        }
    }

    public static void clear() {
        MDC.clear();
    }
}
