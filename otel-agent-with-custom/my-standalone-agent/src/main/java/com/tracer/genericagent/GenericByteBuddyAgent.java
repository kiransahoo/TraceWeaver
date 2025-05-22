
package com.tracer.genericagent;


import com.tracer.genericagent.instrumentation.EnhancedGenericMethodAdvisor;
import com.tracer.genericagent.instrumentation.SystemMetrics;
import com.tracer.genericagent.instrumentation.TraceFilteringSpanProcessor;
import com.tracer.genericagent.util.ConfigReader;


import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;

import io.opentelemetry.exporter.jaeger.JaegerGrpcSpanExporter;


import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GenericByteBuddyAgent that:
 * - Validates Azure connection before setup
 * - Reads config for exporter (Jaeger/Azure), span processor, sampling ratio
 * - Sets up OTel SDK
 * - Installs ByteBuddy instrumentation
 * @author kiransahoo
 */
public class GenericByteBuddyAgent {

    // HTTP client configuration for connection pooling
    private static final PoolingHttpClientConnectionManager connectionManager;
    private static final CloseableHttpClient httpClient;
    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final int SOCKET_TIMEOUT_MS = 10000;

    // Memory monitoring thresholds
    private static final double MEMORY_WARNING_THRESHOLD = 0.75; // 75% memory usage
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.85; // 85% memory usage

    // Static initializer for HTTP client
    static {
        // Initialize connection pool
        connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(10);           // Total max connections
        connectionManager.setDefaultMaxPerRoute(10);
        connectionManager.setValidateAfterInactivity(0);// Max connections per route

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECTION_TIMEOUT_MS)
                .setSocketTimeout(SOCKET_TIMEOUT_MS)
                .setConnectionRequestTimeout(CONNECTION_TIMEOUT_MS)
                .build();

        httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setKeepAliveStrategy((response, context) -> 30 * 1000) // 30 second keepalive
                .build();

        // Register shutdown hook to close the client
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.err.println("[HTTP Client] Shutting down connection manager");
                httpClient.close();
                connectionManager.close();
            } catch (IOException e) {
                System.err.println("[HTTP Client] Error closing HTTP client: " + e.getMessage());
            }
        }));
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            // Print environment info
            printEnvironmentInfo();

            // read config
            List<String> packages = ConfigReader.getPackagePrefixes();
            List<String> includes = ConfigReader.getMethodIncludes();
            List<String> excludes = ConfigReader.getMethodExcludes();
            boolean strictMode = ConfigReader.isStrictMode();

            // Validate configuration
            validateConfiguration(packages);

            // Check exporter type
            String exporterType = ConfigReader.getExporterType();

            // Validate Azure connection if using Azure exporter
            if ("azure".equalsIgnoreCase(exporterType)) {
                String connStr = ConfigReader.getAzureConnectionString();
                boolean connectionValid = validateAzureConnection(connStr);

                if (!connectionValid) {
                    System.err.println("\n=========================================================");
                    System.err.println("⚠️  WARNING: Azure connection validation FAILED");
                    System.err.println("    Data may not be appearing in Application Insights");
                    System.err.println("    Check instrumentation key and network connectivity");
                    System.err.println("=========================================================\n");
                }
            }

            // Set up OpenTelemetry before installing instrumentation
            setupOpenTelemetry();  // sets GlobalOpenTelemetry

            // install ByteBuddy instrumentation
            EnhancedGenericMethodAdvisor.install(inst, packages, includes, excludes);

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
        System.err.println("[VALIDATION] Testing Azure connection...");

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

            System.err.println("[VALIDATION] Testing connection to: " + ingestionEndpoint);
            System.err.println("[VALIDATION] Using instrumentation key: " +
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

            // Use the HTTP client instead of creating a new connection
            HttpPost request = new HttpPost(ingestionEndpoint);
            request.setHeader("Content-Type", "application/json");
            request.setHeader("Accept", "application/json");
            request.setEntity(new StringEntity(payload, "UTF-8"));

            HttpResponse response = httpClient.execute(request);
            int responseCode = response.getStatusLine().getStatusCode();
            String responseMessage = response.getStatusLine().getReasonPhrase();

            // Consume entity to release connection
            EntityUtils.consume(response.getEntity());

            System.err.println("[VALIDATION] HTTP response: " + responseCode + " " + responseMessage);

            boolean success = (responseCode >= 200 && responseCode < 300);
            if (success) {
                System.err.println("[VALIDATION] Connection test successful - Azure endpoint is reachable");
                System.err.println("[VALIDATION] Test event ID: " + testEventId);
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
        System.err.println("\n========== Generic Agent Environment Info ==========");
        System.err.println("Java Version: " + System.getProperty("java.version"));
        System.err.println("Java VM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.err.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.err.println("Working Directory: " + System.getProperty("user.dir"));
        System.err.println("====================================================\n");
    }

    private static void validateConfiguration(List<String> packages) {
        if (packages.isEmpty()) {
            System.err.println("⚠️ WARNING: No packages configured for instrumentation! Agent will not instrument any classes.");
        }

        boolean hasSafeConfiguration = false;
        for (String pkg : packages) {
            if (pkg.startsWith("com.myorg")) {
                hasSafeConfiguration = true;
                break;
            }
        }

        if (!hasSafeConfiguration) {
            System.err.println("   Consider using strictMode=true to avoid unintentional instrumentation of third-party code.");
        }
    }

    private static void printUsageInstructions() {
        System.err.println("\n========== Usage Instructions ==========");
        System.err.println("If using with JBoss/WildFly, add this to your startup command:");
        System.err.println("  -Xbootclasspath/a:jboss-logmanager.jar");
        System.err.println("  -Djava.util.logging.manager=org.jboss.logmanager.LogManager");
        System.err.println("\nIf using with Tomcat, add this to your startup command:");
        System.err.println("  -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager");
        System.err.println("========================================\n");
    }

    private static void setupOpenTelemetry() {
        try {
            // 1) Resource
            Resource resource = Resource.builder()
                    .put("service.name", "TraceBuddyAgentService")
                    .put("library.name", "tracebuddy-agent")
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
                    .setSampler(sampler)
                    .addSpanProcessor(spanProcessor)
                    .build();

            SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                    .setResource(resource)
                    .build();

            // 7) Build and register OpenTelemetrySdk using the recommended approach
            // This handles the global registration properly
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setMeterProvider(meterProvider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .buildAndRegisterGlobal(); // This replaces the separate build() and set() calls

            // 9) optional graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("[GenericByteBuddyAgent] Shutdown -> closing tracerProvider & meterProvider");
                spanProcessor.forceFlush().join(5, TimeUnit.SECONDS);
                tracerProvider.shutdown();
                meterProvider.shutdown();
            }));

        } catch (IllegalStateException e) {
            // This will happen if another agent already registered the SDK
            System.err.println("[GenericByteBuddyAgent] OpenTelemetry SDK already registered by another agent");
            System.err.println("[GenericByteBuddyAgent] Using existing OpenTelemetry configuration");
        }

        // 10) Start system metrics regardless of who set up the SDK
        SystemMetrics.registerGauges();
    }

    private static SpanExporter buildExporterFromConfig() {
        String exporterType = ConfigReader.getExporterType(); // "jaeger" or "azure"

        switch (exporterType.toLowerCase()) {
            case "azure":
                String connStr = ConfigReader.getAzureConnectionString();
                if (connStr == null || connStr.isEmpty()) {
                    System.err.println("Azure connection string not set! Falling back to Jaeger default.");
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

        try {
            return buildFilteredJaegerExporter(endpoint);
        } catch (Exception e) {
            System.err.println("[GenericByteBuddyAgent] Error creating Jaeger exporter: " + e.getMessage());
            return NoopSpanExporter.getInstance();
        }
    }

    private static SpanExporter buildAzureExporter(String connStr) {
        // Parse and validate connection string parts
        String instrumentationKey = null;
        String ingestionEndpoint = "https://eastus-8.in.applicationinsights.azure.com/";
        String applicationId = null;

        try {
            if (connStr != null) {
                String[] parts = connStr.split(";");
                for (String part : parts) {
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
            System.err.println("[DEBUG] Error parsing connection string: " + e.getMessage());
        }

        if (instrumentationKey == null || instrumentationKey.isEmpty()) {
            System.err.println("[ERROR] Missing or invalid InstrumentationKey in connection string");
            return NoopSpanExporter.getInstance();
        }

        String azureSdkVersion = ConfigReader.getAzureSdkVersion();

        if ("direct".equalsIgnoreCase(azureSdkVersion)) {
            // Use direct HTTP exporter (more reliable)
            return buildDirectHttpExporter(instrumentationKey, ingestionEndpoint, applicationId);
        } else {
            // Use Azure Monitor SDK exporter (default)
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
     * Check memory pressure and take actions if needed
     */
    private static void checkMemoryPressure(Queue<SpanData> buffer, String endpoint,
                                            String instrumentationKey, ExecutorService executor, int retryCount) {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double memoryUtilization = (double) usedMemory / maxMemory;

        // Log memory stats at debug level
        if (ConfigReader.isDebugEnabled()) {
//            System.err.println("[Memory] Current utilization: " +
//                    String.format("%.2f%%", memoryUtilization * 100) +
//                    " (Used: " + (usedMemory / (1024 * 1024)) + "MB, " +
//                    "Max: " + (maxMemory / (1024 * 1024)) + "MB)");
        }

        // Critical memory pressure - emergency flush and limit buffer growth
        if (memoryUtilization > MEMORY_CRITICAL_THRESHOLD) {
//            System.err.println("[WARNING] Critical memory pressure detected: " +
//                    String.format("%.2f%%", memoryUtilization * 100) +
//                    " - Emergency flush");

            // Emergency flush remaining spans
            flushBuffer(buffer, endpoint, instrumentationKey, executor, retryCount);



        }
        // High memory pressure - trigger immediate flush
        else if (memoryUtilization > MEMORY_WARNING_THRESHOLD) {
//            System.err.println("[WARNING] High memory pressure detected: " +
//                    String.format("%.2f%%", memoryUtilization * 100) +
//                    " - Triggering immediate flush");
            flushBuffer(buffer, endpoint, instrumentationKey, executor, retryCount);
        }
    }

    /**
     * Builds a production-grade direct HTTP exporter with connection pooling
     */
    private static SpanExporter buildDirectHttpExporter(String instrumentationKey, String ingestionEndpoint, String applicationId) {
        if (!ingestionEndpoint.endsWith("/")) {
            ingestionEndpoint += "/";
        }
        final String endpoint = ingestionEndpoint + "v2/track";

        // Get performance-related configurations
        final int retryCount = ConfigReader.getAzureRetryCount();
        final int bufferSize = ConfigReader.getAzureBufferSize();

        // Create executor service for async operations with more threads
        final ExecutorService executor =
                Executors.newFixedThreadPool(4,
                        r -> {
                            Thread t = new Thread(r, "azure-exporter-thread");
                            t.setDaemon(true); // Don't block JVM shutdown
                            return t;
                        });

        // Create unbounded queue
        final Queue<SpanData> buffer = new ConcurrentLinkedQueue<>();

        // Schedule periodic flush task and memory monitoring
        final ScheduledExecutorService scheduler =
                Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "azure-exporter-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        // Add shutdown hook to cleanly close resources
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[DirectExporter] Shutting down executor and scheduler");
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

        // Schedule memory monitoring (every 10 seconds)
        scheduler.scheduleAtFixedRate(() -> {
            checkMemoryPressure(buffer, endpoint, instrumentationKey, executor, retryCount);
        }, 20, 20, TimeUnit.SECONDS);

        // Schedule periodic flush (every 3 seconds - more frequent)
        scheduler.scheduleAtFixedRate(() -> {
            if (!buffer.isEmpty()) {
                flushBuffer(buffer, endpoint, instrumentationKey, executor, retryCount);
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Create and return the exporter
        return new SpanExporter() {
            final String iKey = instrumentationKey;
            final String appId = applicationId;
            final AtomicBoolean isShutdown = new AtomicBoolean(false);

            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {

                    if (spans.isEmpty() || isShutdown.get()) {
                        return CompletableResultCode.ofSuccess();
                    }

                    CompletableResultCode result = new CompletableResultCode();

                    try {
                        // Add all spans to buffer (no offer check since it's unbounded)
                        buffer.addAll(spans);

                        // If buffer gets large, trigger flush
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
                System.err.println("[DirectExporter] Shutdown requested");
                CompletableResultCode result = new CompletableResultCode();
                isShutdown.set(true);

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

    private static SpanExporter buildFilteredJaegerExporter(String endpoint) {
        final SpanExporter jaegerExporter = JaegerGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .build();

        return new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                long startTime = System.currentTimeMillis(); // Use currentTimeMillis for simplicity
                System.err.println("[TIMING-START] export called with " + spans.size() + " spans");


                // Apply the same filtering logic here
                List<SpanData> filteredSpans = filterBatchBySLA(new ArrayList<>(spans));

                if (filteredSpans.isEmpty()) {
                    return CompletableResultCode.ofSuccess();
                }
                long endTime = System.currentTimeMillis();
                System.err.println("[TIMING-END] export took " + (endTime - startTime) +
                        "ms for " + spans.size() + " spans");
                return jaegerExporter.export(filteredSpans);
            }

            @Override
            public CompletableResultCode flush() {
                return jaegerExporter.flush();
            }

            @Override
            public CompletableResultCode shutdown() {
                return jaegerExporter.shutdown();
            }
        };
    }

    /**
     * Helper method to flush the buffer of spans to Azure using connection pooling
     */
    private static void flushBuffer(Queue<SpanData> buffer, String endpoint,
                                    String instrumentationKey, ExecutorService executor,
                                    int retryCount) {

            int count = 0;
            int batchSize = 400; // Increased from 20 for better performance
            List<SpanData> batch = new ArrayList<>(batchSize);

            while (!buffer.isEmpty() && count < 1000) { // Increased limit to handle more spans per flush
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

    }

    /**
     * Send a batch of spans with retry logic and filtering using connection pooling
     */
    private static void sendBatchWithRetry(List<SpanData> batch, String endpoint,
                                           String instrumentationKey, int maxRetries) {
        // Apply filtering before sending

            List<SpanData> filteredBatch = filterBatchBySLA(batch);

            // If all spans were filtered out, skip sending
            if (filteredBatch.isEmpty()) {
                return;
            }

            int retries = 0;
            boolean success = false;

            while (!success && retries <= maxRetries) {
                try {
                    // Convert to batch payload - use the filtered batch
                    String payload = convertBatchToAIFormat(filteredBatch, instrumentationKey);

                    // Use HTTP client with connection pooling
                    HttpPost request = new HttpPost(endpoint);
                    request.setHeader("Content-Type", "application/json");
                    request.setEntity(new StringEntity(payload, "UTF-8"));

                    HttpResponse response = httpClient.execute(request);
                    int responseCode = response.getStatusLine().getStatusCode();

                    // Consume entity to release connection back to pool
                    EntityUtils.consume(response.getEntity());

                    if (responseCode >= 200 && responseCode < 300) {
                        success = true;
                    } else {
                        System.err.println("[DirectExporter] Failed to send batch: HTTP " + responseCode);
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
     * Filter a batch of spans based on SLA and error criteria
     */
    private static List<SpanData> filterBatchBySLA(List<SpanData> batch) {
        // Get configuration
//        boolean slaFilteringEnabled = ConfigReader.isSlaFilteringEnabled();
//        long slaThresholdMs = ConfigReader.getSlaThresholdMs();
//        boolean exceptionFilteringEnabled = ConfigReader.isExceptionFilteringEnabled();
//
//        // Fast path if no filtering is enabled
//        if (!slaFilteringEnabled && !exceptionFilteringEnabled) {
//            return batch;
//        }
//
//        // Group spans by trace ID for processing
//        Map<String, List<SpanData>> spansByTrace = new HashMap<>();
//        Map<String, Boolean> traceHasError = new HashMap<>();
//        Map<String, Long> traceMaxDuration = new HashMap<>();
//
//        // Analyze spans to collect trace data
//        for (SpanData span : batch) {
//            String traceId = span.getTraceId();
//
//            // Group spans by trace
//            spansByTrace.computeIfAbsent(traceId, k -> new ArrayList<>()).add(span);
//
//            // Check for errors
//            if (span.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR) {
//                traceHasError.put(traceId, true);
//            }
//
//            // Calculate duration
//            long durationNanos = span.getEndEpochNanos() - span.getStartEpochNanos();
//            long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
//
//            // Update max duration for trace
//            traceMaxDuration.compute(traceId, (k, v) ->
//                    v == null ? durationMs : Math.max(v, durationMs));
//        }
//
//        // Build filtered batch
//        List<SpanData> filteredBatch = new ArrayList<>();
//
//        // Process each trace
//        for (String traceId : spansByTrace.keySet()) {
//            boolean keepTrace = false;
//
//            // Check SLA threshold if enabled
//            if (slaFilteringEnabled && traceMaxDuration.getOrDefault(traceId, 0L) >= slaThresholdMs) {
//                keepTrace = true;
//            }
//
//            // Check for errors if enabled
//            if (exceptionFilteringEnabled && Boolean.TRUE.equals(traceHasError.get(traceId))) {
//                keepTrace = true;
//            }
//
//            // Add all spans for this trace if we're keeping it
//            if (keepTrace) {
//                filteredBatch.addAll(spansByTrace.get(traceId));
//            }
//        }

        return batch;
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
     * Includes SLA breach handling that passes the traceId
     */
    private static String convertSpanToAIJson(SpanData span, String instrumentationKey, boolean isRequest) {
        Instant startTime = Instant.ofEpochSecond(0, span.getStartEpochNanos());
        Duration duration = Duration.ofNanos(span.getEndEpochNanos() - span.getStartEpochNanos());
        String traceId = span.getTraceId();

        // Calculate duration in milliseconds for SLA check
        long durationMs = TimeUnit.NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos());
        long slaThresholdMs = ConfigReader.getSlaThresholdMs();
        boolean isSlaBreached = durationMs >= slaThresholdMs;

        // Format duration as App Insights expects: "00:00:00.123"
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        int millis = duration.getNano() / 1_000_000;

        String formattedDuration = String.format("%02d:%02d:%02d.%03d",
                hours, minutes, seconds, millis);

        // Create properties JSON with additional SLA info if breached
        String propertiesJson = convertAttributesToJson(span.getAttributes());
        if (isSlaBreached) {
            // Add SLA-specific properties with traceId
            // Remove the closing brace
            if (propertiesJson.endsWith("}")) {
                propertiesJson = propertiesJson.substring(0, propertiesJson.length() - 1);
                // Add SLA breach properties including traceId
                propertiesJson += String.format("%s\"sla.breach\":true,\"sla.threshold_ms\":%d,\"sla.duration_ms\":%d,\"ai.event.name\":\"SLABreach\",\"traceId\":\"%s\"}",
                        propertiesJson.length() > 1 ? "," : "", slaThresholdMs, durationMs, traceId);
            }
        }

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
                    getAttributeValue(span.getAttributes(), "service.name", "ClaimsApp"),
                    span.getSpanId(),
                    span.getName(),
                    formattedDuration,
                    span.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.OK,
                    propertiesJson
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
                    getAttributeValue(span.getAttributes(), "service.name", "ClaimsApp"),
                    span.getName(),
                    span.getSpanId(),
                    formattedDuration,
                    span.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.OK,
                    span.getName(),
                    propertiesJson
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

        // Create the base processor based on configuration
        SpanProcessor baseProcessor;
        if ("simple".equalsIgnoreCase(processorType)) {
            baseProcessor = SimpleSpanProcessor.create(exporter);
        } else {
            // default = batch
            baseProcessor = BatchSpanProcessor.builder(exporter)
                    .setScheduleDelay(100, TimeUnit.MILLISECONDS) // Flush more frequently
                    .setMaxExportBatchSize(512) // Reasonable batch size
                    .setMaxQueueSize(2048) // Prevent excessive memory use
                    .build();
        }

        // Wrap the base processor with our trace filtering processor
        boolean slafilteringenabled = ConfigReader.isSlaFilteringEnabled();
        if(slafilteringenabled)
        {
            return new TraceFilteringSpanProcessor(baseProcessor);
        }
        return baseProcessor;
    }

    private static Sampler buildSamplerFromConfig() {
        double ratio = ConfigReader.getSamplerRatio(); // default = 1.0
        return Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
    }
}