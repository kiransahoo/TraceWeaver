package com.tracer.genericagent.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import net.bytebuddy.implementation.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * Ultra-minimal interceptor for environments with severe restrictions.
 * Maximum compatibility with minimal features.
 */
@Deprecated
public class MinimalInterceptor {

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("generic-agent");

    /**
     * Simplified intercept method with minimal dependencies and features.
     */
    @RuntimeType
    public static Object intercept(
            @Origin Method method,
            @SuperCall Callable<?> zuper) throws Throwable {

        String methodName = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        Span span = null;
        Scope scope = null;

        try {
            // Create and activate span
            span = tracer.spanBuilder(methodName).startSpan();
            scope = span.makeCurrent();

            // Call the original method
            return zuper.call();
        } catch (Throwable t) {
            // Record exception if span exists
            if (span != null) {
                span.recordException(t);
            }
            throw t;
        } finally {
            // Clean up span and scope
            if (span != null) {
                span.end();
            }
            if (scope != null) {
                scope.close();
            }
        }
    }
}