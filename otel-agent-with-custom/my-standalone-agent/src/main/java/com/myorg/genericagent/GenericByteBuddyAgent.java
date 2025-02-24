package com.myorg.genericagent;

import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
import com.myorg.genericagent.instrumentation.GenericMethodAdvisor;
import com.myorg.genericagent.instrumentation.SystemMetrics;
import com.myorg.genericagent.util.ConfigReader;

// If you're on azure-monitor-opentelemetry-exporter:1.0.0-beta.5, keep the "TraceExporterBuilder"
//import com.azure.monitor.opentelemetry.exporter.AzureMonitorTraceExporter;
//import com.azure.monitor.opentelemetry.exporter.AzureMonitorTraceExporterBuilder;
import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
// If you upgraded to 1.0.0-beta.12, you'd import AzureMonitorExporterBuilder instead

//import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporter;
import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;

import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.TimeUnit;
//import java.util.logging.Logger;

/**
 * GenericByteBuddyAgent that:
 * - Reads config for exporter (Jaeger/Azure), span processor (batch/simple), sampling ratio
 * - Sets up OTel SDK
 * - Installs ByteBuddy instrumentation
 */
public class GenericByteBuddyAgent {
   // private static final Logger logger = Logger.getLogger(GenericByteBuddyAgent.class.getName());

    public static void premain(String agentArgs, Instrumentation inst) {
        // read config
        List<String> packages = ConfigReader.getPackagePrefixes();
        List<String> includes = ConfigReader.getMethodIncludes();
        List<String> excludes = ConfigReader.getMethodExcludes();

//        logger.info(() -> String.format(
//                "[GenericByteBuddyAgent] Packages=%s, includeMethods=%s, excludeMethods=%s",
//                packages, includes, excludes
//        ));


        System.out.println("[GenericByteBuddyAgent] Packages=" + packages
                + ", includeMethods=" + includes
                + ", excludeMethods=" + excludes);
        setupOpenTelemetry();  // sets GlobalOpenTelemetry

        // install ByteBuddy instrumentation
        GenericMethodAdvisor.install(inst, packages, includes, excludes);

       // logger.info("[GenericByteBuddyAgent] Agent installed. ByteBuddy + OTel instrumentation active.");
        System.out.println("[GenericByteBuddyAgent] Agent installed. ByteBuddy + OTel instrumentation active.");
    }

    private static void setupOpenTelemetry() {
        // 1) Resource
        Resource resource = Resource.builder()
                .put("service.name", "GenericByteBuddyAgentService")
                .build();

        // 2) Build the SpanExporter (Jaeger or Azure) based on config
        SpanExporter exporter = buildExporterFromConfig();

        // 3) Build the SpanProcessor (batch or simple)
        SpanProcessor spanProcessor = buildSpanProcessorFromConfig(exporter);

        // 4) Build a Sampler
        Sampler sampler = buildSamplerFromConfig();

        // 5) Create SdkTracerProvider
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler)  // if there's a parent, we respect parent's decision
                .addSpanProcessor(spanProcessor)
                .build();

        // 6) Meter provider for system metrics
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(
                        PeriodicMetricReader.builder(LoggingMetricExporter.create())
                                .setInterval(5, TimeUnit.SECONDS)
                                .build()
                )
                .build();

        // 7) Build OpenTelemetrySdk
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        // 8) Set as global
        GlobalOpenTelemetry.set(sdk);

        // 9) optional graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[GenericByteBuddyAgent] Shutdown -> closing tracerProvider & meterProvider");
            tracerProvider.shutdown();
            meterProvider.shutdown();
        }));

        // 10) Start system metrics
        SystemMetrics.registerGauges();

        System.out.println("[GenericByteBuddyAgent] OTel setup done with config-based exporter, processor, sampler.");
    }

    private static SpanExporter buildExporterFromConfig() {
        String exporterType = ConfigReader.getExporterType(); // "jaeger" or "azure"
        System.out.println("[GenericByteBuddyAgent] exporter.type=" + exporterType);

        switch (exporterType.toLowerCase()) {
            case "azure":
                String connStr = ConfigReader.getAzureConnectionString();
                if (connStr == null || connStr.isEmpty()) {
                    System.out.println("Azure connection string not set! Falling back to Jaeger default.");
                    return buildJaegerExporter();
                }
                return buildAzureExporter(connStr);

            case "jaeger":
            default:
                return buildJaegerExporter();
        }
    }

    private static SpanExporter buildJaegerExporter() {
        String endpoint = ConfigReader.getJaegerEndpoint(); // default http://localhost:14250
        System.out.println("[GenericByteBuddyAgent] Using Jaeger Exporter endpoint=" + endpoint);

        return JaegerGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();
    }

    /**
     * If using azure-monitor-opentelemetry-exporter:1.0.0-beta.5,
     * we have AzureMonitorTraceExporterBuilder class.
     * If on a newer version (like 1.0.0-beta.12), you might need AzureMonitorExporterBuilder.
     */
    private static SpanExporter buildAzureExporter(String connStr) {
        System.out.println("[GenericByteBuddyAgent] Using AzureMonitorTraceExporter, connStr=" + connStr);

        // If on beta.5
//        return new AzureMonitorTraceExporterBuilder()
//                .connectionString(connStr)
//                .build();


        return new AzureMonitorExporterBuilder()
                .connectionString(connStr)
                .buildTraceExporter();

        // If on a newer version, it might be:
        // return new AzureMonitorExporterBuilder()
        //         .connectionString(connStr)
        //         .build();
    }

    private static SpanProcessor buildSpanProcessorFromConfig(SpanExporter exporter) {
        String processorType = ConfigReader.getSpanProcessorType(); // "batch" or "simple"
        System.out.println("[GenericByteBuddyAgent] span.processor=" + processorType);

        if ("simple".equalsIgnoreCase(processorType)) {
            return SimpleSpanProcessor.create(exporter);
        }
        // default = batch
        return BatchSpanProcessor.builder(exporter).build();
    }

    private static Sampler buildSamplerFromConfig() {
        double ratio = ConfigReader.getSamplerRatio(); // default = 1.0
        System.out.println("[GenericByteBuddyAgent] sampler.ratio=" + ratio);

        // Instead of referencing TraceIdRatioBasedSampler directly,
        // use the stable approach in OTel 1.28.0:
        // Sampler.traceIdRatioBased(ratio)
        // Then wrap in parentBased(...) so we respect parent decision if it exists
        return Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
    }
}
