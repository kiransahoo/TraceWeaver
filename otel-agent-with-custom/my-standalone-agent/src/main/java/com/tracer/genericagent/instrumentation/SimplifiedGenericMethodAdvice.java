package com.tracer.genericagent.instrumentation;

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

/**
 * A simplified version of the original GenericMethodAdvice with improved
 * parent-child relationship tracking.
 */
public class SimplifiedGenericMethodAdvice {

    // Static initialization block to verify class loading
    static {
        System.out.println("[DEBUG] *** SimplifiedGenericMethodAdvice class loaded ***");
    }

    // ============= PUBLIC STATIC FIELDS FOR MODULE ACCESS =============
    // These must be public static at the top level to avoid module access issues

    // Using stack-based approach for proper parent-child relationship
    public static final ThreadLocal<Deque<SpanInfo>> ACTIVE_SPANS_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    // Static configuration values with defaults - must be public
    public static final String SERVICE_NAME = "MyOrgApplication";
    public static final String SERVICE_NAMESPACE = "com.myorg.app";
    public static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);
    public static final String ENVIRONMENT = "production";
    public static final String APP_VERSION = "1.0.0";

    /**
     * Get the tracer from the current GlobalOpenTelemetry instance.
     * This ensures compatibility with other agents that might have set up OpenTelemetry.
     * MUST BE PUBLIC STATIC for accessibility from instrumented classes.
     */
    public static Tracer getTracer() {
        try {
            return GlobalOpenTelemetry.get().getTracer("generic-agent-tracer", "1.0.0");
        } catch (Exception e) {
            System.err.println("[GenericMethodAdvice] Error getting global tracer: " + e.getMessage());
            // Create a fallback no-op tracer
            return GlobalOpenTelemetry.getTracerProvider().get("generic-agent-fallback");
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
            //System.out.println("[DEBUG] ENTER intercepted: " + methodName);

            // Split method name into class and method parts
            String className = methodName.substring(0, methodName.lastIndexOf('.'));
            String methodNameOnly = methodName.substring(methodName.lastIndexOf('.') + 1);

            // Generate operation IDs for Azure correlation
            String operationId = UUID.randomUUID().toString();

            // Get the tracer dynamically
            Tracer tracer = getTracer();

            // Important: Get the current context - this might contain a span from the OTel agent
            Context currentContext = Context.current();
            Span parentSpan = Span.fromContext(currentContext);

            // Create builder with proper parent context
            SpanBuilder spanBuilder = tracer.spanBuilder(methodName)
                    .setSpanKind(SpanKind.INTERNAL);

            // If parent span is valid (not null or empty), explicitly set it as parent
            if (parentSpan != null && parentSpan.getSpanContext().isValid() &&
                    !parentSpan.getSpanContext().getTraceId().equals("00000000000000000000000000000000")) {
                // Use the parent context for this span - critical for distributed tracing!
                spanBuilder.setParent(currentContext);
                System.out.println("[DEBUG] Found valid parent span: " + parentSpan.getSpanContext().getTraceId());
            } else {
                // No valid parent, create a new root span
                System.out.println("[DEBUG] No valid parent span found, creating root span");
            }

            // Start the span
//            Span span = spanBuilder
//                    // Azure Application Insights specific attributes
//                    .setAttribute("ai.operation.id", operationId)
//                    .setAttribute("ai.operation.name", methodName)
//                    .setAttribute("ai.cloud.role", SERVICE_NAME)
//                    .setAttribute("ai.cloud.roleInstance", INSTANCE_ID)
//                    .setAttribute("ai.application.ver", APP_VERSION)
//                    .setAttribute("ai.operation.parentId", parentSpan.getSpanContext().isValid() ?
//                            parentSpan.getSpanContext().getSpanId() : "")
//
//                    // Microsoft-specific AI attributes
//                    .setAttribute("ms.operation.id", operationId)
//                    .setAttribute("ms.operation.name", methodName)
//
//                    // Standard OpenTelemetry attributes
//                    .setAttribute("operation.name", methodName)
//                    .setAttribute("operation.id", operationId)
//                    .setAttribute("service.name", SERVICE_NAME)
//                    .setAttribute("service.namespace", SERVICE_NAMESPACE)
//                    .setAttribute("service.instance.id", INSTANCE_ID)
//                    .setAttribute("service.version", APP_VERSION)
//                    .setAttribute("deployment.environment", ENVIRONMENT)
//
//                    // Code specific attributes
//                    .setAttribute("code.namespace", className)
//                    .setAttribute("code.function", methodNameOnly)
//                    .setAttribute("component", "generic-agent")
//
//                    // Telemetry SDK info
//                    .setAttribute("telemetry.sdk.name", "opentelemetry")
//                    .setAttribute("telemetry.sdk.language", "java")
//                    .setAttribute("telemetry.sdk.version", "1.28.0")
//
//                    .startSpan();

            Span span = spanBuilder
                    // Essential Azure Application Insights attributes
                    .setAttribute("ai.operation.id", operationId)
                    .setAttribute("ai.operation.name", methodName)
                    .setAttribute("ai.cloud.role", SERVICE_NAME)
                    .setAttribute("ai.operation.parentId", parentSpan.getSpanContext().isValid() ?
                            parentSpan.getSpanContext().getSpanId() : "")

                    // Essential service identification (keep only one set)
                    .setAttribute("service.name", SERVICE_NAME)

                    // Method identification (essential for debugging)
                    .setAttribute("code.namespace", className)
                    .setAttribute("code.function", methodNameOnly)

                    // Add traceId explicitly as an attribute for easy filtering

                    .startSpan();

           span .setAttribute("trace.id", span.getSpanContext().getTraceId());
            // Make the new span current
            Scope scope = span.makeCurrent();

            // Push to stack for parent-child tracking
            ACTIVE_SPANS_STACK.get().push(new SpanInfo(span, scope));

            // Log the method entry
            System.out.println("[GenericMethodAdvice] ENTER: " + methodName +
                    " --> traceId=" + span.getSpanContext().getTraceId() +
                    ", spanId=" + span.getSpanContext().getSpanId() +
                    ", operationId=" + operationId);

        } catch (Throwable t) {
            // Log error but catch all exceptions to prevent app impact
            System.out.println("[GenericMethodAdvice] Error on method entry: " + t.getMessage());
            t.printStackTrace();
        }
    }

//    /**
//     * Method entry advice with parent-child relationship tracking.
//     */
//    @Advice.OnMethodEnter(suppress = Throwable.class)
//    public static void onEnter(@Advice.Origin("#t.#m") String methodName) {
//        try {
//            System.out.println("[DEBUG] ENTER intercepted: " + methodName);
//
//            // Split method name into class and method parts
//            String className = methodName.substring(0, methodName.lastIndexOf('.'));
//            String methodNameOnly = methodName.substring(methodName.lastIndexOf('.') + 1);
//
//            // Generate operation IDs for Azure correlation
//            String operationId = UUID.randomUUID().toString();
//
//            // Get the tracer dynamically
//            Tracer tracer = getTracer();
//
//            // Create builder with proper parent context
//            SpanBuilder spanBuilder = tracer.spanBuilder(methodName)
//                    .setSpanKind(SpanKind.INTERNAL);
//
//            // Start the span
//            Span span = spanBuilder
//                    // Azure Application Insights specific attributes
//                    .setAttribute("ai.operation.id", operationId)
//                    .setAttribute("ai.operation.name", methodName)
//                    .setAttribute("ai.cloud.role", SERVICE_NAME)
//                    .setAttribute("ai.cloud.roleInstance", INSTANCE_ID)
//                    .setAttribute("ai.application.ver", APP_VERSION)
//                    .setAttribute("ai.operation.parentId", Span.current().getSpanContext().isValid() ?
//                            Span.current().getSpanContext().getSpanId() : "")
//
//                    // Microsoft-specific AI attributes
//                    .setAttribute("ms.operation.id", operationId)
//                    .setAttribute("ms.operation.name", methodName)
//
//                    // Standard OpenTelemetry attributes
//                    .setAttribute("operation.name", methodName)
//                    .setAttribute("operation.id", operationId)
//                    .setAttribute("service.name", SERVICE_NAME)
//                    .setAttribute("service.namespace", SERVICE_NAMESPACE)
//                    .setAttribute("service.instance.id", INSTANCE_ID)
//                    .setAttribute("service.version", APP_VERSION)
//                    .setAttribute("deployment.environment", ENVIRONMENT)
//
//                    // Code specific attributes
//                    .setAttribute("code.namespace", className)
//                    .setAttribute("code.function", methodNameOnly)
//                    .setAttribute("component", "generic-agent")
//
//                    // Telemetry SDK info
//                    .setAttribute("telemetry.sdk.name", "opentelemetry")
//                    .setAttribute("telemetry.sdk.language", "java")
//                    .setAttribute("telemetry.sdk.version", "1.28.0")
//
//                    .startSpan();
//
//            // Make the new span current
//            Scope scope = span.makeCurrent();
//
//            // Push to stack for parent-child tracking
//            ACTIVE_SPANS_STACK.get().push(new SpanInfo(span, scope));
//
//            // Log the method entry
//            System.out.println("[GenericMethodAdvice] ENTER: " + methodName +
//                    " --> traceId=" + span.getSpanContext().getTraceId() +
//                    ", spanId=" + span.getSpanContext().getSpanId() +
//                    ", operationId=" + operationId);
//
//        } catch (Throwable t) {
//            // Log error but catch all exceptions to prevent app impact
//            System.out.println("[GenericMethodAdvice] Error on method entry: " + t.getMessage());
//            t.printStackTrace();
//        }
//    }

    /**
     * Method exit advice with enhanced error handling.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Origin("#t.#m") String methodName,
            @Advice.Thrown Throwable thrown) {
        try {
            System.out.println("[DEBUG] EXIT intercepted: " + methodName);

            // Get and remove the current span from stack
            Deque<SpanInfo> stack = ACTIVE_SPANS_STACK.get();
            if (stack.isEmpty()) {
                System.out.println("[DEBUG] No active span found for method: " + methodName);
                return;
            }

            SpanInfo info = stack.pop();
            Span span = info.span;
            Scope scope = info.scope;

            try {
                // Record exception if any
                if (thrown != null) {
                    span.setStatus(StatusCode.ERROR, thrown.getMessage() != null ?
                            thrown.getMessage() : thrown.getClass().getSimpleName());
                    span.recordException(thrown);
                    span.setAttribute("error", true);
                    span.setAttribute("error.type", thrown.getClass().getName());
                    span.setAttribute("error.message", thrown.getMessage() != null ?
                            thrown.getMessage() : thrown.getClass().getSimpleName());
                    span.setAttribute("ai.operation.isSuccessful", false);
                } else {
                    span.setStatus(StatusCode.OK);
                    span.setAttribute("ai.operation.isSuccessful", true);
                }

                // Add duration info (in milliseconds)
                long durationMs = System.currentTimeMillis() - info.startTimeMs;
                span.setAttribute("duration_ms", durationMs);

                // Log the method exit
                System.out.println("[GenericMethodAdvice] EXIT: " + methodName +
                        " --> traceId=" + span.getSpanContext().getTraceId() +
                        ", spanId=" + span.getSpanContext().getSpanId() +
                        ", duration=" + durationMs + "ms");
            } finally {
                // Always clean up the current span
                span.end();
                scope.close();

                // Clean up ThreadLocal if stack is empty
                if (stack.isEmpty()) {
                    ACTIVE_SPANS_STACK.remove();
                }
            }
        } catch (Throwable t) {
            // Log error but catch all exceptions
            System.out.println("[GenericMethodAdvice] Error on method exit: " + t.getMessage());
            t.printStackTrace();
        }
    }
}