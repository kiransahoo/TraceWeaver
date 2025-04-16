package com.tracer.genericagent.instrumentation;

import com.tracer.genericagent.util.ConfigReader;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.SpanContext;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * Trace filtering processor that makes export decisions at the trace level
 * based on SLA thresholds and/or exceptions.
 */
public class TraceFilteringSpanProcessor implements SpanProcessor {
    private final SpanProcessor delegate;

    // Configuration flags
    private final boolean slaFilteringEnabled;
    private final long slaThresholdMs;
    private final boolean exceptionFilteringEnabled;

    // Data structures for tracking traces
    private final Map<String, TraceInfo> activeTraces = new ConcurrentHashMap<>();
    private final Map<String, Boolean> traceDecisions = new ConcurrentHashMap<>();
    private final Map<String, Queue<ReadableSpan>> pendingSpans = new ConcurrentHashMap<>();

    // Executor for cleanup
    private final ScheduledExecutorService cleanupExecutor;

    // The time threshold for forcing a decision on traces (3 seconds)
    private static final long DECISION_TIMEOUT_MS = 3000;

    /**
     * Creates a new trace filtering processor
     */
    public TraceFilteringSpanProcessor(SpanProcessor delegate) {
        this.delegate = delegate;

        // Load configuration
        this.slaFilteringEnabled = ConfigReader.isSlaFilteringEnabled();
        this.slaThresholdMs = ConfigReader.getSlaThresholdMs();
        this.exceptionFilteringEnabled = ConfigReader.isExceptionFilteringEnabled();

        System.out.println("[TraceFilter] Initialized with: " +
                "slaFilteringEnabled=" + slaFilteringEnabled +
                ", slaThresholdMs=" + slaThresholdMs +
                ", exceptionFilteringEnabled=" + exceptionFilteringEnabled);

        // Schedule cleanup for stale traces
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trace-filter-cleanup");
            t.setDaemon(true);
            return t;
        });

        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleTraces,
                5, 5, TimeUnit.SECONDS);  // Run more frequently (every 5 seconds)

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cleanupExecutor.shutdown();
        }));
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Always delegate onStart
        delegate.onStart(parentContext, span);



        // Get trace ID and current time
        SpanContext spanContext = span.getSpanContext();
        String traceId = spanContext.getTraceId();
        long currentTimeMs = System.currentTimeMillis();

        // Update or create trace info
        activeTraces.compute(traceId, (id, info) -> {
            if (info == null) {
                // New trace
                return new TraceInfo(currentTimeMs, currentTimeMs, false);
            } else {
                // Existing trace - update earliest start time if needed
                return new TraceInfo(
                        Math.min(info.earliestStartTimeMs, currentTimeMs),
                        Math.max(info.latestEndTimeMs, currentTimeMs),
                        info.hasError
                );
            }
        });
    }

    @Override
    public boolean isStartRequired() {
        return delegate.isStartRequired();
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Extract span data
        SpanContext spanContext = span.getSpanContext();
        String traceId = spanContext.getTraceId();
        long currentTimeMs = System.currentTimeMillis();

        // Check span status for errors
        boolean hasError = spanContext.getTraceFlags().isSampled() &&
                (span.toString().contains("ERROR") ||
                        span.toString().contains("StatusCode.ERROR"));

        // If this trace already has a decision, follow it
        Boolean decision = traceDecisions.get(traceId);
        if (decision != null) {
            if (decision) {
                // Trace already decided to be exported
                delegate.onEnd(span);
            }
            return;
        }

        // Root span detection logic
        boolean isRootSpan = false;
        try {
            // First try the string approach for backward compatibility
            isRootSpan = !span.toString().contains("parent");

            // Also treat spans that have certain attributes as root spans
            if (!isRootSpan) {
                // Check for HTTP server spans, which are typical entry points
                String kind = null;
                try {
                    kind = span.toString().contains("SERVER") ? "SERVER" : null;
                } catch (Exception e) {
                    // Ignore if we can't access this
                }

                isRootSpan = "SERVER".equals(kind);
            }
        } catch (Exception e) {
            // Fall back to the original approach if anything goes wrong
            isRootSpan = !span.toString().contains("parent");
            System.out.println("[TraceFilter] Error in root span detection: " + e.getMessage());
        }

        // Update trace info
        TraceInfo updatedInfo = activeTraces.compute(traceId, (id, info) -> {
            if (info == null) {
                return new TraceInfo(currentTimeMs, currentTimeMs, hasError);
            } else {
                return new TraceInfo(
                        info.earliestStartTimeMs,
                        Math.max(info.latestEndTimeMs, currentTimeMs),
                        info.hasError || hasError
                );
            }
        });

        // Queue this span for later processing
        pendingSpans.computeIfAbsent(traceId, k -> new ConcurrentLinkedQueue<>())
                .add(span);

        // If this is a root span or has error, make trace decision immediately
        if (isRootSpan || hasError) {
            makeTraceDecision(traceId, updatedInfo);
        } else {
            // For non-root spans without errors, check if the trace has been active
            // for longer than our decision timeout
            long age = currentTimeMs - updatedInfo.earliestStartTimeMs;
            if (age > DECISION_TIMEOUT_MS) {
                System.out.println("[TraceFilter] Making decision for trace older than " +
                        (DECISION_TIMEOUT_MS/1000) + "s: " + traceId);
                makeTraceDecision(traceId, updatedInfo);
            }
        }
    }

    /**
     * Makes a final decision on whether to export all spans in a trace
     */
    private void makeTraceDecision(String traceId, TraceInfo info) {
        // Calculate trace duration
        long durationMs = info.latestEndTimeMs - info.earliestStartTimeMs;

        // Always check for SLA breaches regardless of filtering setting
        if (durationMs >= slaThresholdMs) {
            System.out.println("[SLA ALERT] Trace " + traceId + " exceeded SLA threshold: " +
                    durationMs + "ms > " + slaThresholdMs + "ms");

            // Add SLA breach notification for Azure Monitor alerts
            Queue<ReadableSpan> spans = pendingSpans.get(traceId);
            if (spans != null && !spans.isEmpty()) {
                for (ReadableSpan span : spans) {
                    if (span instanceof ReadWriteSpan) {
                        ((ReadWriteSpan) span).setAttribute("sla.breach", true);
                        ((ReadWriteSpan) span).setAttribute("sla.threshold_ms", slaThresholdMs);
                        ((ReadWriteSpan) span).setAttribute("sla.duration_ms", durationMs);
                        ((ReadWriteSpan) span).setAttribute("ai.event.name", "SLABreach");
                        break; // Only need to mark one span for the alert
                    }
                }
            }
        }


        // Determine if we should export this trace
        boolean shouldExport = false;

        // Check SLA threshold if enabled
        if (slaFilteringEnabled && durationMs >= slaThresholdMs) {
            shouldExport = true;
            System.out.println("[TraceFilter] Trace exceeded SLA threshold: " +
                    traceId + " (" + durationMs + "ms > " + slaThresholdMs + "ms)");
        }

        // Check for errors if enabled
        if (exceptionFilteringEnabled && info.hasError) {
            shouldExport = true;
            System.out.println("[TraceFilter] Trace has error(s): " + traceId);
        }

        // If no filtering is enabled, export everything
        if (!slaFilteringEnabled && !exceptionFilteringEnabled) {
            shouldExport = true;
        }

        // Record decision
        traceDecisions.put(traceId, shouldExport);

        // Process pending spans based on decision
        Queue<ReadableSpan> spans = pendingSpans.remove(traceId);
        if (spans != null) {
            if (shouldExport) {
                for (ReadableSpan span : spans) {
                    delegate.onEnd(span);
                }
                System.out.println("[TraceFilter] Exporting " + spans.size() +
                        " spans for trace: " + traceId);
            } else {
                System.out.println("[TraceFilter] Filtered out " + spans.size() +
                        " spans for trace: " + traceId);
            }
        }
    }

    /**
     * Cleans up stale traces that haven't received updates in a while
     * and makes decisions for traces that have been active for some time
     */
    private void cleanupStaleTraces() {
        long now = System.currentTimeMillis();
        long staleCutoffMs = now - TimeUnit.MINUTES.toMillis(5); // 5 minute cutoff
        long decisionCutoffMs = now - DECISION_TIMEOUT_MS; // Decision timeout cutoff

        // Process all active traces
        for (Map.Entry<String, TraceInfo> entry : activeTraces.entrySet()) {
            String traceId = entry.getKey();
            TraceInfo info = entry.getValue();

            // If no decision has been made yet for this trace
            if (!traceDecisions.containsKey(traceId)) {
                if (info.latestEndTimeMs < staleCutoffMs) {
                    // If trace is stale, make a decision and clean it up
                    System.out.println("[TraceFilter] Cleaning up stale trace: " + traceId);
                    makeTraceDecision(traceId, info);
                    activeTraces.remove(traceId);
                } else if (info.earliestStartTimeMs < decisionCutoffMs) {
                    // If trace has been active longer than the decision timeout,
                    // make a decision but keep it in active traces
                    System.out.println("[TraceFilter] Making decision for active trace: " + traceId);
                    makeTraceDecision(traceId, info);
                }
            }
        }
    }

    @Override
    public boolean isEndRequired() {
        return delegate.isEndRequired();
    }

    @Override
    public CompletableResultCode shutdown() {
        // Process any pending decisions before shutting down
        forceFlush().join(5, TimeUnit.SECONDS);
        cleanupExecutor.shutdown();
        return delegate.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        // Process any pending decisions
        for (Map.Entry<String, TraceInfo> entry : activeTraces.entrySet()) {
            String traceId = entry.getKey();
            if (!traceDecisions.containsKey(traceId)) {
                makeTraceDecision(traceId, entry.getValue());
            }
        }

        return delegate.forceFlush();
    }

    /**
     * Helper class to track information about a trace
     */
    private static class TraceInfo {
        final long earliestStartTimeMs;
        final long latestEndTimeMs;
        final boolean hasError;

        TraceInfo(long earliestStartTimeMs, long latestEndTimeMs, boolean hasError) {
            this.earliestStartTimeMs = earliestStartTimeMs;
            this.latestEndTimeMs = latestEndTimeMs;
            this.hasError = hasError;
        }
    }
}