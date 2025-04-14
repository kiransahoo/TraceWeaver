package com.tracer.genericagent.instrumentation;

import com.tracer.genericagent.util.ConfigReader;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * High-performance tracing driver for production environments.
 * Optimized for minimal overhead and maximum compatibility.
 */
public class ProductionTracingDriver {

    // Private constructor to prevent instantiation
    private ProductionTracingDriver() {}

    // Pre-allocate attribute keys to avoid allocation during trace collection
    private static final AttributeKey<String> CLASS_ATTR = AttributeKey.stringKey("code.namespace");
    private static final AttributeKey<String> METHOD_ATTR = AttributeKey.stringKey("code.function");

    // Cache of loaded Tracers to avoid repeated lookups
    private static final ConcurrentHashMap<String, Tracer> TRACER_CACHE = new ConcurrentHashMap<>();

    /**
     * Install instrumentation with maximum performance and compatibility
     */
    public static void install(
            Instrumentation instrumentation,
            List<String> packages,
            List<String> excludeMethodPatterns) {

        // Get the sampler ratio to potentially do early filtering
        double samplerRatio = ConfigReader.getSamplerRatio();
        boolean tracingEnabled = samplerRatio > 0.0;

        if (!tracingEnabled) {
            System.out.println("[ProductionTracingDriver] Tracing disabled (sampler.ratio=0)");
            return;
        }

        System.out.println("[ProductionTracingDriver] Installing optimized instrumentation");

        try {
            AgentBuilder agentBuilder = new AgentBuilder.Default()
                    .disableClassFormatChanges() // For maximum compatibility
                    .with(AgentBuilder.RedefinitionStrategy.DISABLED)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
                            // Only log errors, but keep them minimal
                            String errorMsg = throwable.getMessage();
                            if (errorMsg != null && errorMsg.length() > 100) {
                                errorMsg = errorMsg.substring(0, 100) + "..."; // Truncate long error messages
                            }
                            System.out.println("[ProductionTracingDriver] Error: " + typeName + ": " + errorMsg);
                        }
                    });

            // Build matchers for each package but exclude system packages
            for (String pkg : packages) {
                // Add specific type matcher for this package
                ElementMatcher.Junction<TypeDescription> typeMatcher = nameStartsWith(pkg)
                        .and(not(isInterface()))
                        .and(not(isAbstract()))
                        .and(not(nameContains("$"))); // Avoid inner/synthetic classes

                // Build method matcher to exclude specific methods and types that aren't worth tracing
                ElementMatcher.Junction methodMatcher = isPublic()
                        .and(not(isConstructor()))
                        .and(not(isStatic().and(nameStartsWith("main")))) // Skip main methods
                        .and(not(isGetter().or(isSetter()))); // Skip trivial accessors

                // Add user-configured method exclusions
                if (excludeMethodPatterns != null && !excludeMethodPatterns.isEmpty()) {
                    for (String pattern : excludeMethodPatterns) {
                        methodMatcher = methodMatcher.and(not(nameContains(pattern)));
                    }
                }

                ElementMatcher.Junction finalMethodMatcher = methodMatcher;
                agentBuilder = agentBuilder
                        .type(typeMatcher)
                        .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                            if (classLoader == null) {
                                return builder;
                            }

                            try {
                                // Apply optimized advice
                                return builder
                                        .method(finalMethodMatcher)
                                        .intercept(Advice.to(HighPerformanceAdvice.class));
                            } catch (Exception e) {
                                return builder; // Continue even if this type fails
                            }
                        });
            }

            // Install the agent
            agentBuilder.installOn(instrumentation);
            System.out.println("[ProductionTracingDriver] Instrumentation installed successfully");

        } catch (Exception e) {
            System.out.println("[ProductionTracingDriver] Installation error: " + e.getMessage());
        }
    }

    /**
     * Get or create a tracer for the specified component
     */
    private static Tracer getTracer(String component) {
        return TRACER_CACHE.computeIfAbsent(component,
                name -> GlobalOpenTelemetry.getTracer("generic-agent-" + name, "1.0.0"));
    }

    /**
     * High-performance advice class optimized for production use
     */
    public static class HighPerformanceAdvice {

        // Thread local storage is more efficient than passing spans via locals
        private static final ThreadLocal<TraceInfo> TRACE_INFO = new ThreadLocal<>();

        private static class TraceInfo {
            final Span span;
            final Scope scope;
            final long startTimeNanos;

            TraceInfo(Span span, Scope scope) {
                this.span = span;
                this.scope = scope;
                this.startTimeNanos = System.nanoTime();
            }
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.Origin("#t") String className,
                                 @Advice.Origin("#m") String methodName) {
            try {
                // Avoid object allocation for non-sampled traces
                // Get tracer - reuse cached instance for the class's package
                String packageName = getPackageName(className);
                Tracer tracer = getTracer(packageName);

                // Build span with minimal attributes
                SpanBuilder spanBuilder = tracer.spanBuilder(className + "." + methodName)
                        .setSpanKind(SpanKind.INTERNAL);

                // Add parent context if available
                Context parentContext = Context.current();
                if (parentContext != null) {
                    spanBuilder.setParent(parentContext);
                }

                // Start span with minimal attributes
                Span span = spanBuilder.startSpan();

                // Only set attributes that are truly needed
                span.setAttribute(CLASS_ATTR, className);
                span.setAttribute(METHOD_ATTR, methodName);

                // Make span the current context and store for method exit
                Scope scope = span.makeCurrent();
                TRACE_INFO.set(new TraceInfo(span, scope));

            } catch (Throwable t) {
                // Silently handle errors - never impact application
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Thrown Throwable throwable) {
            try {
                TraceInfo info = TRACE_INFO.get();
                if (info != null) {
                    try {
                        Span span = info.span;

                        // Record exceptions if present
                        if (throwable != null) {
                            span.setStatus(StatusCode.ERROR);
                            span.recordException(throwable);
                        }

                        // End the span
                        span.end();

                        // Close the scope
                        info.scope.close();
                    } finally {
                        // Always clean up thread local
                        TRACE_INFO.remove();
                    }
                }
            } catch (Throwable t) {
                // Silently handle errors - never impact application
            }
        }

        /**
         * Extract package name from fully qualified class name.
         * Performance optimized to avoid extra object allocation.
         */
        private static String getPackageName(String className) {
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                return className.substring(0, lastDot);
            }
            return "default";
        }
    }
}