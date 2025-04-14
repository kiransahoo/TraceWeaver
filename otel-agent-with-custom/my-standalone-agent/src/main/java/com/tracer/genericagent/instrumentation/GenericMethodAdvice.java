package com.tracer.genericagent.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * Production-grade advice for method instrumentation with OpenTelemetry.
 * Features robust error handling, detailed span attributes, and proper context propagation.
 */
public class GenericMethodAdvice {

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("generic-agent-tracer", "1.0.0");

    private static final AttributeKey<String> CLASS_NAME_KEY = AttributeKey.stringKey("code.namespace");
    private static final AttributeKey<String> METHOD_NAME_KEY = AttributeKey.stringKey("code.function");
    private static final AttributeKey<Boolean> ERROR_KEY = AttributeKey.booleanKey("error");

    /**
     * Thread-local storage for span and scope to handle nested method calls properly
     * while avoiding ByteBuddy parameter handling issues.
     */
    private static final ThreadLocal<SpanInfo> ACTIVE_SPANS = new ThreadLocal<>();

    private static class SpanInfo {
        final Span span;
        final Scope scope;
        final String methodName;

        SpanInfo(Span span, Scope scope, String methodName) {
            this.span = span;
            this.scope = scope;
            this.methodName = methodName;
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onMethodEnter(@Advice.Origin("#t") String className,
                                     @Advice.Origin("#m") String methodName) {
        try {
            // Extract class and method names
            String fullMethodName = className + "." + methodName;

            // Create span with meaningful name and attributes
            Span span = tracer.spanBuilder(fullMethodName)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(Context.current())
                    .setAttribute(CLASS_NAME_KEY, className)
                    .setAttribute(METHOD_NAME_KEY, methodName)
                    .startSpan();

            // Make span the current context
            Scope scope = span.makeCurrent();

            // Store in thread-local for retrieval in exit advice
            ACTIVE_SPANS.set(new SpanInfo(span, scope, fullMethodName));

            // Minimal logging to avoid performance impact
            if (System.getProperty("trace.debug") != null) {
                System.out.println("[Trace] Started: " + fullMethodName);
            }
        } catch (Throwable t) {
            // Silent failure - never impact the application
            System.out.println("[Trace] Error in method entry: " + t.getMessage());
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMethodExit(@Advice.Thrown Throwable throwable) {
        try {
            SpanInfo info = ACTIVE_SPANS.get();
            if (info != null) {
                Span span = info.span;
                Scope scope = info.scope;

                try {
                    // Record exception if present
                    if (throwable != null) {
                        span.setStatus(StatusCode.ERROR);
                        span.setAttribute(ERROR_KEY, true);
                        span.recordException(throwable);
                    }

                    // Minimal logging to avoid performance impact
                    if (System.getProperty("trace.debug") != null) {
                        System.out.println("[Trace] Completed: " + info.methodName);
                    }
                } finally {
                    // Always ensure resources are properly closed
                    span.end();
                    scope.close();
                    ACTIVE_SPANS.remove();
                }
            }
        } catch (Throwable t) {
            // Silent failure - never impact the application
            System.out.println("[Trace] Error in method exit: " + t.getMessage());
        }
    }
}