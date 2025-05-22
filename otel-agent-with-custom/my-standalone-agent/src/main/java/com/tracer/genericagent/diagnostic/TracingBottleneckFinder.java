package com.tracer.genericagent.diagnostic;

import com.tracer.genericagent.util.ConfigReader;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic tool to find performance bottlenecks in the tracing pipeline
 */
public class TracingBottleneckFinder {

    private static final String LOG_FILE = "tracing_perf.csv";
    private static final PrintWriter logWriter;

    static {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE));
            logWriter.println("timestamp,component,duration_ms,spans,trace_ids,thread");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize performance logging", e);
        }

        // Register shutdown hook to close writer
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Closing performance log file");
            logWriter.close();
        }));
    }

    /**
     * Install this method at the start of any method you want to profile
     */
    public static TimingContext startTiming(String componentName) {
        return new TimingContext(componentName);
    }

    /**
     * Record detailed timing information about spans
     */
    public static void recordDetailedSpanInfo(Collection<SpanData> spans, String component) {
        try {
            // Extract unique trace IDs
            Set<String> traceIds = new HashSet<>();
            Map<String, Integer> spanCountsByTrace = new HashMap<>();

            for (SpanData span : spans) {
                String traceId = span.getTraceId();
                traceIds.add(traceId);
                spanCountsByTrace.put(traceId, spanCountsByTrace.getOrDefault(traceId, 0) + 1);
            }

            // Log trace sizes
            StringBuilder traceInfo = new StringBuilder();
            for (Map.Entry<String, Integer> entry : spanCountsByTrace.entrySet()) {
                if (traceInfo.length() > 0) traceInfo.append(", ");
                traceInfo.append(entry.getKey().substring(0, 6)).append("=").append(entry.getValue());
            }

            System.out.println("[PERF-DETAIL] " + component +
                    " - Spans: " + spans.size() +
                    ", Traces: " + traceIds.size() +
                    ", TraceInfo: " + traceInfo);
        } catch (Exception e) {
            // Don't let diagnostics crash the application
            System.err.println("Error in detailed span recording: " + e.getMessage());
        }
    }

    /**
     * Timing context for method profiling
     */
    public static class TimingContext implements AutoCloseable {
        private final String componentName;
        private final long startTimeNanos;
        private final Thread currentThread;
        private int spanCount;
        private int traceCount;
        private static final AtomicInteger counter = new AtomicInteger();
        private final int id;

        TimingContext(String componentName) {
            this.componentName = componentName;
            this.startTimeNanos = System.nanoTime();
            this.currentThread = Thread.currentThread();
            this.id = counter.incrementAndGet();
        }

        public TimingContext withSpans(Collection<?> spans) {
            this.spanCount = spans != null ? spans.size() : 0;
            return this;
        }

        public TimingContext withTraces(int traceCount) {
            this.traceCount = traceCount;
            return this;
        }

        @Override
        public void close() {
            long durationNanos = System.nanoTime() - startTimeNanos;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);

            // Only log significant durations (>1ms) to reduce noise
            if (durationMs >= 1) {
                String threadName = currentThread.getName();
                String timestamp = String.valueOf(System.currentTimeMillis());

                // Log to console and file
                String consoleLog = String.format("[PERF] %s took %dms (spans=%d, traces=%d, thread=%s)",
                        componentName, durationMs, spanCount, traceCount, threadName);

                System.out.println(consoleLog);

                // Log to CSV for later analysis
                synchronized (logWriter) {
                    logWriter.println(timestamp + "," +
                            componentName + "," +
                            durationMs + "," +
                            spanCount + "," +
                            traceCount + "," +
                            threadName);
                    logWriter.flush();
                }
            }
        }
    }
}