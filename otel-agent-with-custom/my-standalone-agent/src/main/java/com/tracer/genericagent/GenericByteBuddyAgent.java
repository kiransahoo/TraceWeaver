package com.tracer.genericagent;

import com.azure.monitor.opentelemetry.exporter.AzureMonitorExporterBuilder;
import com.tracer.genericagent.instrumentation.EnhancedGenericMethodAdvisor;
import com.tracer.genericagent.instrumentation.SystemMetrics;
import com.tracer.genericagent.util.ConfigReader;

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
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GenericByteBuddyAgent that:
 * - Validates Azure connection before setup
 * - Reads config for exporter (Jaeger/Azure), span processor, sampling ratio
 * - Sets up OTel SDK
 * - Installs ByteBuddy instrumentation
 */
public class GenericByteBuddyAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            // Print environment information
            printEnvironmentInfo();

            // read config
            List<String> packages = ConfigReader.getPackagePrefixes();
            List<String> includes = ConfigReader.getMethodIncludes();
            List<String> excludes = ConfigReader.getMethodExcludes();
            boolean strictMode = ConfigReader.isStrictMode();

            System.out.println("[GenericByteBuddyAgent] Packages=" + packages
                    + ", includeMethods=" + includes
                    + ", excludeMethods=" + excludes
                    + ", strictMode=" + strictMode);

            // Validate configuration
            validateConfiguration(packages);

            // Check exporter type
            String exporterType = ConfigReader.getExporterType();
            System.out.println("[GenericByteBuddyAgent] exporter.type=" + exporterType);

            // Validate Azure connection if using Azure exporter
            if ("azure".equalsIgnoreCase(exporterType)) {
                String connStr = ConfigReader.getAzureConnectionString();
                boolean connectionValid = validateAzureConnection(connStr);

                if (!connectionValid) {
                    System.err.println("\n=========================================================");
                    System.err.println("⚠️  WARNING: Azure connection validation FAILED");
                    System.err.println("    Data may not be appearing in your Application Insights");
                    System.err.println("    Check your instrumentation key and network connectivity");
                    System.err.println("=========================================================\n");
                } else {
                    System.out.println("\n=========================================================");
                    System.out.println("✅ Azure connection validation SUCCESSFUL");
                    System.out.println("   Data should appear in your Application Insights shortly");
                    System.out.println("=========================================================\n");
                }
            }

            // Set up OpenTelemetry before installing instrumentation
            setupOpenTelemetry();  // sets GlobalOpenTelemetry

            // install ByteBuddy instrumentation
            EnhancedGenericMethodAdvisor.install(inst, packages, includes, excludes);

            System.out.println("[GenericByteBuddyAgent] Agent installed. ByteBuddy + OTel instrumentation active.");

            // Show usage instructions for any JBoss or Tomcat environments
            printUsageInstructions();

        } catch (Throwable t) {
            // Catch and log any errors during agent startup to prevent application crashes
            System.err.println("[GenericByteBuddyAgent] ERROR during agent initialization: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Validates Azure connection by directly sending a test event to the Application Insights endpoint.
     * This helps confirm that the instrumentation key is valid and the endpoint is reachable.
     *
     * @param connStr The Azure connection string
     * @return true if validation was successful, false otherwise
     */
    private static boolean validateAzureConnection(String connStr) {
        System.out.println("[VALIDATION] Testing Azure connection...");

        if (connStr == null || connStr.isEmpty()) {
            System.err.println("[VALIDATION] Connection string is empty or null");
            return false;
        }

        try {
            // Extract the instrumentation key and endpoint from connection string
            String instrumentationKey = null;
            String ingestionEndpoint = "https://eastus-8.in.applicationinsights.azure.com/";

            String[] parts = connStr.split(";");
            for (String part : parts) {
                if (part.startsWith("InstrumentationKey=")) {
                    instrumentationKey = part.substring("InstrumentationKey=".length());
                } else if (part.startsWith("IngestionEndpoint=")) {
                    ingestionEndpoint = part.substring("IngestionEndpoint=".length());
                }
            }

            if (instrumentationKey == null) {
                System.err.println("[VALIDATION] No InstrumentationKey found in connection string");
                return false;
            }

            // Ensure endpoint ends with /v2/track for the API
            if (!ingestionEndpoint.endsWith("/")) {
                ingestionEndpoint += "/";
            }
            ingestionEndpoint += "v2/track";

            System.out.println("[VALIDATION] Testing connection to: " + ingestionEndpoint);
            System.out.println("[VALIDATION] Using instrumentation key: " +
                    instrumentationKey.substring(0, 6) + "..." +
                    instrumentationKey.substring(instrumentationKey.length() - 4));

            // Create test event payload
            String testEventId = UUID.randomUUID().toString();
            String payload = String.format(
                    "{\"name\":\"MessageData\",\"time\":\"%s\",\"iKey\":\"%s\",\"tags\":{\"ai.cloud.role\":\"ValidationTest\"},\"data\":{\"baseType\":\"MessageData\",\"baseData\":{\"ver\":2,\"message\":\"Connection test %s\",\"properties\":{\"TestId\":\"%s\"}}}}",
                    Instant.now().toString(),
                    instrumentationKey,
                    testEventId,
                    testEventId
            );

            // Send direct HTTP request
            URL url = new URL(ingestionEndpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(5000); // 5 second timeout
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);

            // Write payload
            try (OutputStream os = connection.getOutputStream()) {
                os.write(payload.getBytes("UTF-8"));
                os.flush();
            }

            // Get response
            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            System.out.println("[VALIDATION] HTTP response: " + responseCode + " " + responseMessage);

            boolean success = (responseCode >= 200 && responseCode < 300);
            if (success) {
                System.out.println("[VALIDATION] Connection test successful - Azure endpoint is reachable");
                System.out.println("[VALIDATION] Test event ID: " + testEventId);
                System.out.println("[VALIDATION] Look for this event in your Application Insights logs");
            } else {
                System.err.println("[VALIDATION] Connection test FAILED - HTTP " + responseCode);
                System.err.println("[VALIDATION] This may indicate an invalid instrumentation key or network issue");
            }

            return success;
        } catch (Exception e) {
            System.err.println("[VALIDATION] Connection test FAILED with exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static void printEnvironmentInfo() {
        System.out.println("\n========== Generic Agent Environment Info ==========");
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("Java VM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("Working Directory: " + System.getProperty("user.dir"));
        System.out.println("====================================================\n");
    }

    private static void validateConfiguration(List<String> packages) {
        if (packages.isEmpty()) {
            System.out.println("⚠️ WARNING: No packages configured for instrumentation! Agent will not instrument any classes.");
        }

        boolean hasSafeConfiguration = false;
        for (String pkg : packages) {
            if (pkg.startsWith("com.myorg")) {
                hasSafeConfiguration = true;
                break;
            }
        }

        if (!hasSafeConfiguration) {
            System.out.println("⚠️ WARNING: You are not instrumenting any com.myorg.* packages. This may cause unexpected behavior!");
            System.out.println("   Consider using strictMode=true to avoid unintentional instrumentation of third-party code.");
        }
    }

    private static void printUsageInstructions() {
        System.out.println("\n========== Usage Instructions ==========");
        System.out.println("If using with JBoss/WildFly, add this to your startup command:");
        System.out.println("  -Xbootclasspath/a:jboss-logmanager.jar");
        System.out.println("  -Djava.util.logging.manager=org.jboss.logmanager.LogManager");
        System.out.println("\nIf using with Tomcat, add this to your startup command:");
        System.out.println("  -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager");
        System.out.println("========================================\n");
    }

    private static void setupOpenTelemetry() {
        // 1) Resource
        Resource resource = Resource.builder()
                .put("service.name", "GenericByteBuddyAgentService")
                .put("library.name", "generic-agent")
                .put("library.version", "1.0.0")
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
//        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
//                .setResource(resource)
//                .registerMetricReader(
//                        PeriodicMetricReader.builder(LoggingMetricExporter.create())
//                                .setInterval(5, TimeUnit.SECONDS)
//                                .build()
//                )
//                .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
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
            spanProcessor.forceFlush().join(5, TimeUnit.SECONDS);
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

        try {
            return JaegerGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .build();
        } catch (Exception e) {
            System.err.println("[GenericByteBuddyAgent] Error creating Jaeger exporter: " + e.getMessage());
            // Provide a no-op exporter as fallback
            return NoopSpanExporter.getInstance();
        }
    }

    private static SpanExporter buildAzureExporter(String connStr) {
        System.out.println("==================== AZURE EXPORTER DETAILS ====================");
        System.out.println("[DEBUG] Creating Azure exporter with connection string: "
                + (connStr != null ? connStr.substring(0, Math.min(50, connStr.length())) + "..." : "null"));

        // Parse and validate connection string parts
        String instrumentationKey = null;
        String ingestionEndpoint = "https://eastus-8.in.applicationinsights.azure.com/";
        String applicationId = null;

        try {
            if (connStr != null) {
                String[] parts = connStr.split(";");
                for (String part : parts) {
                    System.out.println("[DEBUG] Connection string part: " + part);
                    if (part.startsWith("InstrumentationKey=")) {
                        instrumentationKey = part.substring("InstrumentationKey=".length());
                    } else if (part.startsWith("IngestionEndpoint=")) {
                        ingestionEndpoint = part.substring("IngestionEndpoint=".length());
                    } else if (part.startsWith("ApplicationId=")) {
                        applicationId = part.substring("ApplicationId=".length());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Error parsing connection string: " + e.getMessage());
        }

        if (instrumentationKey == null || instrumentationKey.isEmpty()) {
            System.err.println("[ERROR] Missing or invalid InstrumentationKey in connection string");
            return NoopSpanExporter.getInstance();
        }

        String azureSdkVersion = ConfigReader.getAzureSdkVersion();
        System.out.println("[DEBUG] Using Azure SDK Version: " + azureSdkVersion);

        if ("direct".equalsIgnoreCase(azureSdkVersion)) {
            // Use direct HTTP exporter (more reliable)
            return buildDirectHttpExporter(instrumentationKey, ingestionEndpoint, applicationId);
        } else {
            // Use Azure Monitor SDK exporter (default)
            return buildAzureSdkExporter(connStr);
        }
    }

    /**
     * Builds an exporter that uses Azure Monitor SDK
     */
    private static SpanExporter buildAzureSdkExporter(String connStr) {
        try {
            System.out.println("[DEBUG] Creating Azure Monitor exporter builder");
            AzureMonitorExporterBuilder builder = new AzureMonitorExporterBuilder();

            System.out.println("[DEBUG] Setting connection string");
            builder.connectionString(connStr);

            // Add these options if available in your version of the Azure SDK
            // builder.maxTransmissionStorageCapacity(50 * 1024 * 1024) // 50MB buffer
            // builder.enableDiskPersistence(true)  // Persist if sending fails

            System.out.println("[DEBUG] Building trace exporter");
            final SpanExporter azureExporter = builder.buildTraceExporter();
            System.out.println("[DEBUG] Azure SDK exporter created successfully: " + azureExporter.getClass().getName());

            return buildExporterWithDebugLogging(azureExporter);
        } catch (Exception e) {
            System.err.println("[DEBUG] Error creating Azure SDK exporter: " + e.getMessage());
            e.printStackTrace();

            // If SDK exporter fails, fall back to direct HTTP exporter
            System.out.println("[DEBUG] Falling back to direct HTTP exporter");
            String instrumentationKey = extractInstrumentationKey(connStr);
            String ingestionEndpoint = extractIngestionEndpoint(connStr);
            String applicationId = extractApplicationId(connStr);
            return buildDirectHttpExporter(instrumentationKey, ingestionEndpoint, applicationId);
        }
    }

    /**
     * Helper to extract instrumentation key from connection string
     */
    private static String extractInstrumentationKey(String connStr) {
        if (connStr == null) return null;
        for (String part : connStr.split(";")) {
            if (part.startsWith("InstrumentationKey=")) {
                return part.substring("InstrumentationKey=".length());
            }
        }
        return null;
    }

    /**
     * Helper to extract ingestion endpoint from connection string
     */
    private static String extractIngestionEndpoint(String connStr) {
        if (connStr == null) return "https://eastus-8.in.applicationinsights.azure.com/";
        for (String part : connStr.split(";")) {
            if (part.startsWith("IngestionEndpoint=")) {
                return part.substring("IngestionEndpoint=".length());
            }
        }
        return "https://eastus-8.in.applicationinsights.azure.com/";
    }

    /**
     * Helper to extract application ID from connection string
     */
    private static String extractApplicationId(String connStr) {
        if (connStr == null) return null;
        for (String part : connStr.split(";")) {
            if (part.startsWith("ApplicationId=")) {
                return part.substring("ApplicationId=".length());
            }
        }
        return null;
    }

    /**
     * Builds a production-grade direct HTTP exporter
     */
    private static SpanExporter buildDirectHttpExporter(String instrumentationKey, String ingestionEndpoint, String applicationId) {
        System.out.println("[DEBUG] Creating direct HTTP exporter");
        System.out.println("[DEBUG] Instrumentation Key: " +
                (instrumentationKey != null ? instrumentationKey.substring(0, 6) + "..." : "null"));
        System.out.println("[DEBUG] Ingestion Endpoint: " + ingestionEndpoint);

        if (!ingestionEndpoint.endsWith("/")) {
            ingestionEndpoint += "/";
        }
        final String endpoint = ingestionEndpoint + "v2/track";

        // Get performance-related configurations
        final int retryCount = ConfigReader.getAzureRetryCount();
        final int bufferSize = ConfigReader.getAzureBufferSize();

        System.out.println("[DEBUG] Direct HTTP exporter configured with:");
        System.out.println("[DEBUG] - Endpoint: " + endpoint);
        System.out.println("[DEBUG] - Retry Count: " + retryCount);
        System.out.println("[DEBUG] - Buffer Size: " + bufferSize);

        // Create executor service for async operations
        final ExecutorService executor =
                Executors.newFixedThreadPool(2,
                        r -> {
                            Thread t = new Thread(r, "azure-exporter-thread");
                            t.setDaemon(true); // Don't block JVM shutdown
                            return t;
                        });

        // Create batching buffer
        final Queue<SpanData> buffer = new ConcurrentLinkedQueue<>();

        // Schedule periodic flush task
        final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "azure-exporter-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        // Add shutdown hook to cleanly close resources
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[DirectExporter] Shutting down executor and scheduler");
            executor.shutdown();
            scheduler.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // Schedule periodic flush (every 5 seconds)
        scheduler.scheduleAtFixedRate(() -> {
            if (!buffer.isEmpty()) {
                flushBuffer(buffer, endpoint, instrumentationKey, executor, retryCount);
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Create and return the exporter
        return new SpanExporter() {
            final String iKey = instrumentationKey;
            final String appId = applicationId;

            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                if (spans.isEmpty()) {
                    return CompletableResultCode.ofSuccess();
                }

                CompletableResultCode result = new CompletableResultCode();

                try {
                    // Add all spans to buffer
                    buffer.addAll(spans);

                    // If buffer exceeds size limit, trigger flush
                    if (buffer.size() >= bufferSize) {
                        executor.submit(() -> {
                            try {
                                flushBuffer(buffer, endpoint, iKey, executor, retryCount);
                                result.succeed();
                            } catch (Exception e) {
                                System.err.println("[DirectExporter] Error in flush: " + e.getMessage());
                                result.fail();
                            }
                        });
                    } else {
                        // Small batch, mark as success immediately
                        result.succeed();
                    }
                } catch (Exception e) {
                    System.err.println("[DirectExporter] Error in export: " + e.getMessage());
                    result.fail();
                }

                return result;
            }

            @Override
            public CompletableResultCode flush() {
                CompletableResultCode result = new CompletableResultCode();

                // Submit flush task to executor
                executor.submit(() -> {
                    try {
                        flushBuffer(buffer, endpoint, iKey, executor, retryCount);
                        result.succeed();
                    } catch (Exception e) {
                        System.err.println("[DirectExporter] Error in manual flush: " + e.getMessage());
                        result.fail();
                    }
                });

                return result;
            }

            @Override
            public CompletableResultCode shutdown() {
                System.out.println("[DirectExporter] Shutdown requested");
                CompletableResultCode result = new CompletableResultCode();

                try {
                    // Only try to flush if the executor is still running
                    if (!executor.isShutdown()) {
                        // Do a final flush directly without submitting new tasks
                        flushBuffer(buffer, endpoint, iKey, executor, retryCount);
                    }

                    // Clean shutdown
                    executor.shutdown();
                    scheduler.shutdown();

                    result.succeed();
                } catch (Exception e) {
                    System.err.println("[DirectExporter] Error during shutdown: " + e.getMessage());
                    result.fail();
                }

                return result;
            }
        };
    }

    /**
     * Helper method to flush the buffer of spans to Azure
     */
    private static void flushBuffer(Queue<SpanData> buffer, String endpoint,
                                    String instrumentationKey, ExecutorService executor,
                                    int retryCount) {
        int count = 0;
        int batchSize = 20; // Process in small batches to avoid large JSON payloads
        List<SpanData> batch = new ArrayList<>(batchSize);

        while (!buffer.isEmpty() && count < 100) { // Limit to 100 spans per flush for safety
            SpanData span = buffer.poll();
            if (span != null) {
                batch.add(span);
                count++;

                // Process batch when it reaches batch size
                if (batch.size() >= batchSize) {
                    final List<SpanData> currentBatch = new ArrayList<>(batch);
                    batch.clear();

                    // Submit batch processing to executor
                    executor.submit(() -> sendBatchWithRetry(currentBatch, endpoint, instrumentationKey, retryCount));
                }
            }
        }

        // Send any remaining spans in batch
        if (!batch.isEmpty()) {
            final List<SpanData> currentBatch = new ArrayList<>(batch);
            executor.submit(() -> sendBatchWithRetry(currentBatch, endpoint, instrumentationKey, retryCount));
        }

        if (count > 0) {
            System.out.println("[DirectExporter] Flushed " + count + " spans");
        }
    }

    /**
     * Send a batch of spans with retry logic
     */
    private static void sendBatchWithRetry(List<SpanData> batch, String endpoint,
                                           String instrumentationKey, int maxRetries) {
        int retries = 0;
        boolean success = false;

        while (!success && retries <= maxRetries) {
            try {
                // Convert to batch payload
                String payload = convertBatchToAIFormat(batch, instrumentationKey);

                // Send via HTTP
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    success = true;
                    System.out.println("[DirectExporter] Successfully sent " + batch.size() + " spans");
                } else {
                    System.err.println("[DirectExporter] Failed to send batch: HTTP " + responseCode);
                    // Read error response
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream()))) {
                        String line;
                        StringBuilder response = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        System.err.println("[DirectExporter] Error response: " + response.toString());
                    } catch (Exception e) {
                        System.err.println("[DirectExporter] Error reading error response: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("[DirectExporter] Error sending batch (retry " + retries + "): " + e.getMessage());
            }

            if (!success) {
                retries++;
                if (retries <= maxRetries) {
                    // Exponential backoff
                    try {
                        long backoffMs = (long) Math.min(1000 * Math.pow(2, retries), 30000);
                        System.out.println("[DirectExporter] Retrying in " + backoffMs + "ms");
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (!success) {
            System.err.println("[DirectExporter] Failed to send batch after " + maxRetries + " retries");
        }
    }

    /**
     * Convert a batch of spans to Application Insights format
     */
    private static String convertBatchToAIFormat(List<SpanData> spans, String instrumentationKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");

        boolean first = true;
        for (SpanData span : spans) {
            if (!first) {
                sb.append(",");
            }
            first = false;

            // Determine if this is a request or dependency span
            boolean isRequest = true; // Default to request type

            // Add the span JSON
            sb.append(convertSpanToAIJson(span, instrumentationKey, isRequest));
        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * Convert a single span to Application Insights JSON format
     */
    private static String convertSpanToAIJson(SpanData span, String instrumentationKey, boolean isRequest) {
        Instant startTime = Instant.ofEpochSecond(0, span.getStartEpochNanos());
        Duration duration = Duration.ofNanos(span.getEndEpochNanos() - span.getStartEpochNanos());

        // Format duration as App Insights expects: "00:00:00.123"
        String formattedDuration = String.format("%02d:%02d:%02d.%03d",
                duration.toHoursPart(),
                duration.toMinutesPart(),
                duration.toSecondsPart(),
                duration.toMillisPart());

        if (isRequest) {
            return String.format(
                    "{" +
                            "\"name\":\"Microsoft.ApplicationInsights.Request\"," +
                            "\"time\":\"%s\"," +
                            "\"iKey\":\"%s\"," +
                            "\"tags\":{" +
                            "\"ai.operation.id\":\"%s\"," +
                            "\"ai.operation.name\":\"%s\"," +
                            "\"ai.internal.sdkVersion\":\"java:otel-agent:1.0.0\"," +
                            "\"ai.cloud.role\":\"%s\"" +
                            "}," +
                            "\"data\":{" +
                            "\"baseType\":\"RequestData\"," +
                            "\"baseData\":{" +
                            "\"ver\":2," +
                            "\"id\":\"%s\"," +
                            "\"name\":\"%s\"," +
                            "\"duration\":\"%s\"," +
                            "\"responseCode\":\"200\"," +
                            "\"success\":%b," +
                            "\"properties\":%s" +
                            "}" +
                            "}" +
                            "}",
                    startTime.toString(),
                    instrumentationKey,
                    span.getTraceId(),
                    span.getName(),
                    getAttributeValue(span.getAttributes(), "service.name", "MyOrgApplication"),
                    span.getSpanId(),
                    span.getName(),
                    formattedDuration,
                    span.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.OK,
                    convertAttributesToJson(span.getAttributes())
            );
        } else {
            // Dependency type span
            return String.format(
                    "{" +
                            "\"name\":\"Microsoft.ApplicationInsights.RemoteDependency\"," +
                            "\"time\":\"%s\"," +
                            "\"iKey\":\"%s\"," +
                            "\"tags\":{" +
                            "\"ai.operation.id\":\"%s\"," +
                            "\"ai.operation.name\":\"%s\"," +
                            "\"ai.internal.sdkVersion\":\"java:otel-agent:1.0.0\"," +
                            "\"ai.cloud.role\":\"%s\"" +
                            "}," +
                            "\"data\":{" +
                            "\"baseType\":\"RemoteDependencyData\"," +
                            "\"baseData\":{" +
                            "\"ver\":2," +
                            "\"name\":\"%s\"," +
                            "\"id\":\"%s\"," +
                            "\"duration\":\"%s\"," +
                            "\"success\":%b," +
                            "\"data\":\"%s\"," +
                            "\"target\":\"\"," +
                            "\"type\":\"InProc\"," +
                            "\"properties\":%s" +
                            "}" +
                            "}" +
                            "}",
                    startTime.toString(),
                    instrumentationKey,
                    span.getTraceId(),
                    span.getName(),
                    getAttributeValue(span.getAttributes(), "service.name", "MyOrgApplication"),
                    span.getName(),
                    span.getSpanId(),
                    formattedDuration,
                    span.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.OK,
                    span.getName(),
                    convertAttributesToJson(span.getAttributes())
            );
        }
    }

    /**
     * Get attribute value with default
     */
    private static String getAttributeValue(io.opentelemetry.api.common.Attributes attributes,
                                            String key, String defaultValue) {
        io.opentelemetry.api.common.AttributeKey<String> attrKey =
                io.opentelemetry.api.common.AttributeKey.stringKey(key);
        String value = attributes.get(attrKey);
        return value != null ? value : defaultValue;
    }

    /**
     * Convert attributes to JSON
     */
    private static String convertAttributesToJson(io.opentelemetry.api.common.Attributes attributes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        boolean first = true;
        for (io.opentelemetry.api.common.AttributeKey<?> key : attributes.asMap().keySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;

            Object value = attributes.get(key);
            sb.append(String.format("\"%s\":", escapeJsonString(key.getKey())));

            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number) {
                sb.append(value.toString());
            } else if (value instanceof Boolean) {
                sb.append(value.toString());
            } else {
                sb.append(String.format("\"%s\"", escapeJsonString(value.toString())));
            }
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Escape special characters in JSON string
     */
    private static String escapeJsonString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Wraps an exporter with debug logging
     */
    private static SpanExporter buildExporterWithDebugLogging(SpanExporter wrapped) {
        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                System.out.println("\n[DEBUG] =====EXPORT START=====");
                System.out.println("[DEBUG] Thread: " + Thread.currentThread().getName());
                System.out.println("[DEBUG] Attempting to export " + spans.size() + " spans to Azure");

                // Log details about each span
                int i = 0;
                for (SpanData span : spans) {
                    i++;
                    System.out.println("[DEBUG] Span #" + i + ":");
                    System.out.println("[DEBUG]   - Name: " + span.getName());
                    System.out.println("[DEBUG]   - TraceId: " + span.getTraceId());
                    System.out.println("[DEBUG]   - SpanId: " + span.getSpanId());
                    System.out.println("[DEBUG]   - Start: " + span.getStartEpochNanos());
                    System.out.println("[DEBUG]   - End: " + span.getEndEpochNanos());
                    System.out.println("[DEBUG]   - Status: " + span.getStatus());
                    System.out.println("[DEBUG]   - Attributes: " + span.getAttributes());
                    System.out.println("[DEBUG]   - Resource: " + span.getResource().getAttributes());
                    // Limit detailed logging if there are many spans
                    if (i >= 3 && spans.size() > 3) {
                        System.out.println("[DEBUG]   ... and " + (spans.size() - 3) + " more spans");
                        break;
                    }
                }

                System.out.println("[DEBUG] Calling Azure exporter.export()");
                long startTime = System.currentTimeMillis();
                CompletableResultCode result = wrapped.export(spans);

                // Add detailed result handling
                result.whenComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    if (result.isSuccess()) {
                        System.out.println("[DEBUG] Successfully exported " + spans.size() +
                                " spans to Azure (took " + duration + "ms)");
                    } else {
                        System.out.println("[DEBUG] Failed to export spans to Azure: " + result +
                                " (after " + duration + "ms)");
                        System.out.println("[DEBUG] Error details: " + result.toString());
                    }
                    System.out.println("[DEBUG] =====EXPORT END=====\n");
                });

                return result;
            }

            @Override
            public CompletableResultCode flush() {
                System.out.println("[DEBUG] Flushing Azure exporter...");
                long startTime = System.currentTimeMillis();
                CompletableResultCode result = wrapped.flush();

                result.whenComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    if (result.isSuccess()) {
                        System.out.println("[DEBUG] Flush completed successfully in " + duration + "ms");
                    } else {
                        System.out.println("[DEBUG] Flush failed after " + duration + "ms: " + result);
                    }
                });

                return result;
            }

            @Override
            public CompletableResultCode shutdown() {
                System.out.println("[DEBUG] Shutting down Azure exporter...");
                CompletableResultCode result = wrapped.shutdown();
                result.whenComplete(() -> {
                    if (result.isSuccess()) {
                        System.out.println("[DEBUG] Shutdown completed successfully");
                    } else {
                        System.out.println("[DEBUG] Shutdown failed: " + result);
                    }
                });
                return result;
            }
        };
    }

    /**
     * Simple no-op SpanExporter implementation for fallback when exporters fail to initialize
     */
    private static class NoopSpanExporter implements SpanExporter {
        private static final NoopSpanExporter INSTANCE = new NoopSpanExporter();

        public static NoopSpanExporter getInstance() {
            return INSTANCE;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static SpanProcessor buildSpanProcessorFromConfig(SpanExporter exporter) {
        String processorType = ConfigReader.getSpanProcessorType(); // "batch" or "simple"
        System.out.println("[GenericByteBuddyAgent] span.processor=" + processorType);

        if ("simple".equalsIgnoreCase(processorType)) {
            return SimpleSpanProcessor.create(exporter);
        }
        // default = batch
        return BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(100, TimeUnit.MILLISECONDS) // Flush more frequently
                .build();
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