package com.tracer.genericagent.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.bytebuddy.implementation.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * Production-grade interceptor for method delegation.
 * Uses ByteBuddy's @RuntimeType annotations for maximum compatibility.
 */
public class TracingInterceptor {

    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("generic-agent-tracer", "1.0.0");

    private static final AttributeKey<String> CLASS_NAME_KEY = AttributeKey.stringKey("code.namespace");
    private static final AttributeKey<String> METHOD_NAME_KEY = AttributeKey.stringKey("code.function");
    private static final AttributeKey<Boolean> ERROR_KEY = AttributeKey.booleanKey("error");

    /**
     * Main intercept method that will be called instead of the original method.
     * Uses ByteBuddy's delegation API for maximum compatibility.
     */
    @RuntimeType
    public static Object intercept(
            @Origin Class<?> clazz,
            @Origin Method method,
            @AllArguments Object[] args,
            @SuperCall Callable<?> zuper) throws Throwable {

        String className = clazz.getName();
        String methodName = method.getName();
        String fullMethodName = className + "." + methodName;

        // Create span with class and method information
        Span span = tracer.spanBuilder(fullMethodName)
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(Context.current())
                .setAttribute(CLASS_NAME_KEY, className)
                .setAttribute(METHOD_NAME_KEY, methodName)
                .startSpan();

        // Make span the current context
        Scope scope = span.makeCurrent();

        try {
            // Call the original method
            return zuper.call();
        } catch (Throwable throwable) {
            // Record exception details in span
            span.setStatus(StatusCode.ERROR);
            span.setAttribute(ERROR_KEY, true);
            span.recordException(throwable);

            // Re-throw the original exception
            throw throwable;
        } finally {
            // Always ensure span is ended and scope is closed
            span.end();
            scope.close();
        }
    }
}