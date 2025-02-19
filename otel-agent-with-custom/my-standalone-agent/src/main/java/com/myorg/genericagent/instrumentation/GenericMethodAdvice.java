package com.myorg.genericagent.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;
import net.bytebuddy.asm.Advice;

import java.util.logging.Logger;
import java.util.Arrays;

/**
 * Generic advice that can run across any methods we intercept,
 * automatically linking them in a single trace if they're on the same thread.
 */
public class GenericMethodAdvice {

    private static final Logger logger = Logger.getLogger(GenericMethodAdvice.class.getName());

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Origin("#t.#m") String methodName,
            @Advice.AllArguments Object[] args,
            @Advice.Local("span") Span span,
            @Advice.Local("scope") Scope scope
    ) {
        Tracer tracer = GlobalOpenTelemetry.getTracer("generic-agent-tracer");

        // Build a new span with the current context as parent
        span = tracer.spanBuilder(methodName)
                .setParent(Context.current())
                .startSpan();

        // Make the new span current
        scope = span.makeCurrent();

        // Log
        Span finalSpan = span;
        logger.info(() -> String.format(
                "[GenericMethodAdvice] ENTER: %s => traceId=%s, spanId=%s, args=%s",
                methodName,
                finalSpan.getSpanContext().getTraceId(),
                finalSpan.getSpanContext().getSpanId(),
                Arrays.toString(args)
        ));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Local("span") Span span,
            @Advice.Local("scope") Scope scope,
            @Advice.Thrown Throwable thrown
    ) {
        // record exception if any
        if (thrown != null) {
            span.recordException(thrown);
        }
        // end the span
        span.end();
        // close the scope
        scope.close();

        logger.info(() -> String.format(
                "[GenericMethodAdvice] EXIT => traceId=%s, spanId=%s",
                span.getSpanContext().getTraceId(),
                span.getSpanContext().getSpanId()
        ));
    }
}
