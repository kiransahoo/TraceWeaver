package com.tracer.genericagent.instrumentation;

import com.tracer.genericagent.util.ConfigReader;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;

import net.bytebuddy.asm.Advice;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simplified version with improved
 * parent-child relationship tracking and configurable error handling.
 */
public class SimplifiedGenericMethodAdvice {



    // Error capture configuration - defaults if properties not found
    public static final boolean ERROR_CAPTURE_ENABLED =
            ConfigReader.getBooleanProperty("error.capture.enabled", true);
    public static final double ERROR_SAMPLE_RATE =
            ConfigReader.getDoubleProperty("error.capture.sample.rate", 0.1); // 10% by default
    public static final int MAX_STACK_LENGTH =
            ConfigReader.getIntProperty("error.capture.max.stack.length", 250); // 250 chars max
    public static final long ERROR_RATE_LIMIT_MS =
            ConfigReader.getLongProperty("error.capture.rate.limit.ms", 60000); // 1 minute
    public static final int MAX_ERROR_SPANS_PER_MINUTE =
            ConfigReader.getIntProperty("error.capture.max.per.minute", 5); // 5 per minute
    public static final boolean VERBOSE_LOGGING =
            ConfigReader.getBooleanProperty("error.capture.verbose.logging", false);

    // Parse ignored exceptions from config
    public static final Set<String> IGNORED_EXCEPTIONS = parseIgnoredExceptions(
            ConfigReader.getProperty("error.capture.ignored.exceptions", "InterruptedException,TimeoutException")
    );

    // ============= RATE LIMITING FOR ERROR SPANS =============

    public static final ConcurrentHashMap<String, Long> ERROR_RATE_LIMITER = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<String, Integer> ERROR_COUNTS = new ConcurrentHashMap<>();

    // Static initialization block to verify class loading
    static {
        System.err.println("[CustomAgent] SimplifiedGenericMethodAdvice loaded - Error capture: " +
                ERROR_CAPTURE_ENABLED + ", Sample rate: " + ERROR_SAMPLE_RATE +
                ", Max stack: " + MAX_STACK_LENGTH);
    }

    // ThreadLocal cleanup thread
    static {
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(180000); // 3 minutes
                    cleanupOrphanedThreadLocals();
                    cleanupRateLimiters();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "custom-agent-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    // ============= PUBLIC STATIC FIELDS FOR MODULE ACCESS =============

    // Using stack-based approach for proper parent-child relationship
    public static final ThreadLocal<Deque<SpanInfo>> ACTIVE_SPANS_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    // Static configuration values with defaults - must be public
    public static final String SERVICE_NAME = ConfigReader.getProperty("service.name", "MyOrgApplication");
    public static final String SERVICE_NAMESPACE = ConfigReader.getProperty("service.namespace", "com.myorg.app");
    public static final String ENVIRONMENT = ConfigReader.getProperty("environment", "production");

    public static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);
    public static final String APP_VERSION = "1.0.0";

    /**
     * Parse ignored exceptions from comma-separated string
     */
    public static Set<String> parseIgnoredExceptions(String ignoredExceptionsStr) {
        if (ignoredExceptionsStr == null || ignoredExceptionsStr.trim().isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(ignoredExceptionsStr.split(",")));
    }


    /**
     * Get the tracer from the current GlobalOpenTelemetry instance.
     * This ensures compatibility with other agents that might have set up OpenTelemetry.
     * MUST BE PUBLIC STATIC for accessibility from instrumented classes.
     */
    public static Tracer getTracer() {
        try {
            Tracer tracer = GlobalOpenTelemetry.get().getTracer("generic-agent-tracer", "1.0.0");
            if (tracer == null) {
                if (VERBOSE_LOGGING) {
                    System.err.println("[CustomAgent] Received null tracer, using fallback");
                }
                return GlobalOpenTelemetry.getTracerProvider().get("generic-agent-fallback");
            }
            return tracer;
        } catch (Exception e) {
            if (VERBOSE_LOGGING) {
                System.err.println("[CustomAgent] Error getting global tracer: " + e.getMessage());
            }
            // Create a fallback tracer using TracerProvider
            return GlobalOpenTelemetry.getTracerProvider().get("generic-agent-fallback");
        }
    }

    /**
     * Check if we should create detailed error span based on rate limiting
     */
    public static boolean shouldCreateDetailedErrorSpan(String errorKey, long currentTime) {
        // Clean up old entries
        ERROR_RATE_LIMITER.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > ERROR_RATE_LIMIT_MS);

        // Count recent errors for this key
        long recentErrors = ERROR_RATE_LIMITER.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(errorKey.split(":")[0])) // Same method
                .count();

        if (recentErrors < MAX_ERROR_SPANS_PER_MINUTE) {
            ERROR_RATE_LIMITER.put(errorKey + ":" + currentTime, currentTime);
            return true;
        }
        return false;
    }

    /**
     * Cleanup rate limiter maps periodically
     */
    public static void cleanupRateLimiters() {
        try {
            long currentTime = System.currentTimeMillis();
            ERROR_RATE_LIMITER.entrySet().removeIf(entry ->
                    currentTime - entry.getValue() > ERROR_RATE_LIMIT_MS);

            // Reset error counts every hour
            if (ERROR_COUNTS.size() > 1000) {
                ERROR_COUNTS.clear();
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Cleanup orphaned ThreadLocal entries to prevent memory leaks
     */
    public static void cleanupOrphanedThreadLocals() {
        try {
            Deque<SpanInfo> stack = ACTIVE_SPANS_STACK.get();
            if (stack != null && !stack.isEmpty()) {
                if (VERBOSE_LOGGING) {
                    System.err.println("[CustomAgent] WARNING: Found " + stack.size() + " orphaned spans, cleaning up");
                }

                // Close all orphaned spans
                while (!stack.isEmpty()) {
                    SpanInfo info = stack.pop();
                    try {
                        if (info.span != null) info.span.end();
                        if (info.scope != null) info.scope.close();
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }
                ACTIVE_SPANS_STACK.remove();
            }
        } catch (Exception e) {
            // Ignore cleanup errors in background thread
        }
    }

    /**
     * Public static class to hold span information.
     */
    public static class SpanInfo {
        public final Span span;
        public final Scope scope;
        public final long startTimeMs;

        public SpanInfo(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
            this.startTimeMs = System.currentTimeMillis();
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin("#t.#m") String methodName) {
        try {

            Deque<SpanInfo> stack = ACTIVE_SPANS_STACK.get();
            if (stack != null && stack.size() > 150) {
                return; // Skip instrumentation if too deep
            }

            // Split method name into class and method parts
            String className = methodName.substring(0, methodName.lastIndexOf('.'));
            String methodNameOnly = methodName.substring(methodName.lastIndexOf('.') + 1);

            // Get the tracer dynamically
            Tracer tracer = getTracer();
            if (tracer == null) {
                return; // Cannot proceed without tracer
            }

            // Important: Get the current context - this might contain a span from the OTel agent
            Context currentContext = Context.current();
            Span parentSpan = Span.fromContext(currentContext);

            // Create builder with proper parent context
            SpanBuilder spanBuilder = tracer.spanBuilder(methodName)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(currentContext);

            String operationId;
            String parentId = "";

            if (parentSpan != null && parentSpan.getSpanContext().isValid() &&
                    !parentSpan.getSpanContext().getTraceId().equals("00000000000000000000000000000000")) {
                // Use the existing trace ID for correlation with App Insights
                operationId = parentSpan.getSpanContext().getTraceId();
                parentId = parentSpan.getSpanContext().getSpanId();
            } else {
                // Only generate new if no parent exists
                operationId = UUID.randomUUID().toString();
            }

            Span span = spanBuilder
                    // Essential Azure Application Insights attributes
                    .setAttribute("ai.operation.name", methodName)
                    .setAttribute("ai.cloud.role", SERVICE_NAME)
                    .setAttribute("ai.operation.parentId", parentId)
                    // Essential service identification
                    .setAttribute("service.name", SERVICE_NAME)
                    // Method identification (essential for debugging)
                    .setAttribute("code.namespace", className)
                    .setAttribute("code.function", methodNameOnly)
                    .startSpan();

            if (span != null) {
                span.setAttribute("trace.id", span.getSpanContext().getTraceId());
                span.setAttribute("ai.operation.id", span.getSpanContext().getTraceId());

                // Make the new span current
                Scope scope = span.makeCurrent();

//                            System.out.println("[GenericMethodAdvice] ENTER: " + methodName +
//                                    " --> traceId=" + span.getSpanContext().getTraceId() +
//                                    ", spanId=" + span.getSpanContext().getSpanId());
                // Push to stack for parent-child tracking
                ACTIVE_SPANS_STACK.get().push(new SpanInfo(span, scope));
            }

        } catch (Throwable t) {
            // Log error but catch all exceptions to prevent app impact
            System.err.println("[CustomAgent] ERROR in onEnter for " + methodName + ": " + t.getMessage());

            // Don't let onEnter failures leak ThreadLocal entries
            try {
                Deque<SpanInfo> stack = ACTIVE_SPANS_STACK.get();
                if (stack != null && !stack.isEmpty()) {
                    SpanInfo lastEntry = stack.peek();
                    // Only clean up if this looks like our failed entry
                    if (lastEntry != null) {
                        stack.pop();
                        try {
                            if (lastEntry.span != null) lastEntry.span.end();
                            if (lastEntry.scope != null) lastEntry.scope.close();
                        } catch (Exception cleanup) {
                            // Ignore cleanup errors
                        }
                    }
                }
            } catch (Exception cleanup) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Method exit advice with enhanced error handling and configurable exception capture.
     */
    /**
     * Method exit advice with enhanced error handling and configurable exception capture.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Origin("#t.#m") String methodName,
            @Advice.Thrown Throwable thrown) {

        SpanInfo info = null;
        Span span = null;
        Scope scope = null;
        Deque<SpanInfo> stack = null; // Declare once at the top

        try {
            // Get and remove the current span from stack
            stack = ACTIVE_SPANS_STACK.get(); // Assign, don't redeclare
            if (stack == null || stack.isEmpty()) {
                // Clean up ThreadLocal even if stack is empty
                ACTIVE_SPANS_STACK.remove();
                return;
            }

            info = stack.pop();
            span = info.span;
            scope = info.scope;

            if (span == null) {
                if (scope != null) {
                    try { scope.close(); } catch (Exception e) { /* ignore */ }
                }
                return;
            }

            try {
                // *** CONFIGURABLE EXCEPTION CAPTURE ***
                if (thrown != null && ERROR_CAPTURE_ENABLED) {
                    handleException(span, thrown, methodName);
                } else if (thrown != null) {
                    // Minimal error info if capture is disabled
                    span.setStatus(StatusCode.ERROR, "Error (capture disabled)");
                    span.setAttribute("error", true);
                    span.setAttribute("ai.operation.isSuccessful", false);
                } else {
                    span.setStatus(StatusCode.OK);
                    span.setAttribute("ai.operation.isSuccessful", true);
                }

                // Add duration info (in milliseconds)
                long durationMs = System.currentTimeMillis() - info.startTimeMs;
                span.setAttribute("duration_ms", durationMs);

            } catch (Exception spanException) {
                // Don't let span operations fail silently
                if (VERBOSE_LOGGING) {
                    System.err.println("[CustomAgent] Error updating span for " + methodName + ": " + spanException.getMessage());
                }
            } finally {
                // ALWAYS clean up resources
                try {
                    if (span != null) span.end();
                } catch (Exception e) {
                    if (VERBOSE_LOGGING) {
                        System.err.println("[CustomAgent] Error ending span: " + e.getMessage());
                    }
                }

                try {
                    if (scope != null) scope.close();
                } catch (Exception e) {
                    if (VERBOSE_LOGGING) {
                        System.err.println("[CustomAgent] Error closing scope: " + e.getMessage());
                    }
                }

                // Clean up ThreadLocal if stack is empty
                // Use existing stack variable, don't redeclare
                if (stack == null || stack.isEmpty()) {
                    ACTIVE_SPANS_STACK.remove();
                }
            }
//            System.out.println("[GenericMethodAdvice] ENTER: " + methodName +
//                    " --> traceId=" + span.getSpanContext().getTraceId() +
//                    ", spanId=" + span.getSpanContext().getSpanId());

        } catch (Throwable t) {
            System.err.println("[CustomAgent] Critical error in onExit for " + methodName + ": " + t.getMessage());

            // Emergency cleanup
            if (span != null) {
                try { span.end(); } catch (Exception e) { /* ignore */ }
            }
            if (scope != null) {
                try { scope.close(); } catch (Exception e) { /* ignore */ }
            }

            // Emergency ThreadLocal cleanup
            try {
                ACTIVE_SPANS_STACK.remove();
            } catch (Exception cleanup) {
                // Ignore cleanup errors
            }
        }
        finally {

            // Ensures ThreadLocal is always cleaned up
            try {
                Deque<SpanInfo> tvstack = ACTIVE_SPANS_STACK.get();
                if (tvstack == null || tvstack.isEmpty()) {
                    ACTIVE_SPANS_STACK.remove(); // Prevent memory leak
                }
            } catch (Exception e) {
                // Ignore any errors in finally
            }
        }
    }

    /**
     * Handle exception capture with rate limiting and sampling
     */
    public static void handleException(Span span, Throwable thrown, String methodName) {
        String exceptionType = thrown.getClass().getSimpleName();

        // Skip ignored exceptions
        if (IGNORED_EXCEPTIONS.contains(exceptionType)) {
            span.setStatus(StatusCode.ERROR, "Ignored exception: " + exceptionType);
            span.setAttribute("error", true);
            span.setAttribute("error.ignored", true);
            span.setAttribute("ai.operation.isSuccessful", false);
            return;
        }

        // Apply sampling
        if (Math.random() > ERROR_SAMPLE_RATE) {
            span.setStatus(StatusCode.ERROR, "Error (sampled out)");
            span.setAttribute("error", true);
            span.setAttribute("error.sampled", true);
            span.setAttribute("error.type", exceptionType);
            span.setAttribute("ai.operation.isSuccessful", false);
            return;
        }

        // Check rate limiting
        String errorKey = methodName + ":" + exceptionType;
        long currentTime = System.currentTimeMillis();

        if (shouldCreateDetailedErrorSpan(errorKey, currentTime)) {
            // Create detailed error span
            createDetailedErrorSpan(span, thrown, methodName);

            if (VERBOSE_LOGGING) {
                System.err.println("[CustomAgent] Exception captured in span for " + methodName + ": " + thrown.getMessage());
            }
        } else {
            // Rate limited - minimal error info
            int errorCount = ERROR_COUNTS.merge(errorKey, 1, Integer::sum);
            span.setStatus(StatusCode.ERROR, "Error (rate limited, count: " + errorCount + ")");
            span.setAttribute("error", true);
            span.setAttribute("error.type", exceptionType);
            span.setAttribute("error.rate_limited", true);
            span.setAttribute("error.count", errorCount);
            span.setAttribute("ai.operation.isSuccessful", false);
        }
    }

    /**
     * Create detailed error span with limited stack trace
     */
    public static void createDetailedErrorSpan(Span span, Throwable thrown, String methodName) {
        String errorMessage = thrown.getMessage();
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            errorMessage = thrown.getClass().getSimpleName() + " (no message)";
        }

        // Set span status and record exception
        span.setStatus(StatusCode.ERROR, errorMessage);
        span.recordException(thrown);

        // Add comprehensive error attributes for Azure Application Insights
        span.setAttribute("error", true);
        span.setAttribute("error.type", thrown.getClass().getName());
        span.setAttribute("error.message", errorMessage);
        span.setAttribute("ai.operation.isSuccessful", false);

        // Add LIMITED stack trace (200-300 characters max)
        String stackTrace = getStackTraceString(thrown);
        if (stackTrace.length() > MAX_STACK_LENGTH) {
            stackTrace = stackTrace.substring(0, MAX_STACK_LENGTH) + "... (truncated)";
        }
        span.setAttribute("error.stack", stackTrace);
    }

    /**
     * Convert exception stack trace to string (helper method) - LIMITED LENGTH
     */
    public static String getStackTraceString(Throwable throwable) {
        if (throwable == null) return "";

        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            throwable.printStackTrace(pw);
            String fullTrace = sw.toString();

            // Return only first few lines of stack trace
            String[] lines = fullTrace.split("\n");
            StringBuilder result = new StringBuilder();
            int maxLines = 3; // Only first 3 lines of stack trace

            for (int i = 0; i < Math.min(maxLines, lines.length); i++) {
                if (i > 0) result.append("\n");
                result.append(lines[i]);
                if (result.length() > MAX_STACK_LENGTH) {
                    break;
                }
            }

            return result.toString();
        } catch (Exception e) {
            return throwable.getClass().getName() + ": " + throwable.getMessage();
        }
    }
}