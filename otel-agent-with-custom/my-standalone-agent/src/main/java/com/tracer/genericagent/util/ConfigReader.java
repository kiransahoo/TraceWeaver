package com.tracer.genericagent.util;
import java.io.InputStream;
import java.util.*;

/**
 * Reads "my-agent-config.properties" from resources (or environment),
 * providing config for:
 *   1) instrumentation (packages, includes, excludes)
 *   2) now also: instrument.excludePackages
 *   3) exporter.type (jaeger|azure)
 *   4) jaeger.endpoint
 *   5) azure.connectionString
 *   6) span.processor (batch|simple)
 *   7) sampler.ratio (0..1)
 *   8) azure.sdk.version (sdk|direct)
 *   9) azure.retry.count
 *   10) azure.buffer.size
 * @author kiransahoo
 */
public class ConfigReader {

    private static final String RESOURCE_PATH = "/my-agent-config.properties";

    // Existing keys
    public static final String KEY_EXPORTER_TYPE = "exporter.type";
    public static final String KEY_JAEGER_ENDPOINT = "jaeger.endpoint";
    public static final String KEY_AZURE_CONNSTRING = "azure.connectionString";
    public static final String KEY_SPAN_PROCESSOR = "span.processor";
    public static final String KEY_SAMPLER_RATIO = "sampler.ratio";

    private static final String KEY_PACKAGES = "instrument.packages";
    private static final String KEY_METHODS_INCLUDE = "instrument.includeMethods";
    private static final String KEY_METHODS_EXCLUDE = "instrument.excludeMethods";

    // Key for excluding packages
    private static final String KEY_PACKAGES_EXCLUDE = "instrument.excludePackages";
    // Key for strict mode (only instrument *.myorg.*)
    private static final String KEY_STRICT_MODE = "instrument.strictMode";

    // New Azure specific configuration keys
    public static final String KEY_AZURE_SDK_VERSION = "azure.sdk.version";
    public static final String KEY_RETRY_COUNT = "azure.retry.count";
    public static final String KEY_BUFFER_SIZE = "azure.buffer.size";

    // Env variable fallbacks
    private static final String ENV_EXPORTER = "OTEL_EXPORTER";
    private static final String ENV_JAEGER_ENDPOINT = "JAEGER_ENDPOINT";
    private static final String ENV_AZURE_CONN = "AZURE_CONNECTION_STRING";
    private static final String ENV_SPAN_PROCESSOR = "OTEL_SPANPROCESSOR";
    private static final String ENV_SAMPLER_RATIO = "OTEL_SAMPLER_RATIO";

    private static final String ENV_PACKAGES = "INSTRUMENT_PACKAGES";
    private static final String ENV_METHODS_INCLUDE = "INSTRUMENT_INCLUDE_METHODS";
    private static final String ENV_METHODS_EXCLUDE = "INSTRUMENT_EXCLUDE_METHODS";
    private static final String ENV_PACKAGES_EXCLUDE = "INSTRUMENT_EXCLUDE_PACKAGES";
    private static final String ENV_STRICT_MODE = "INSTRUMENT_STRICT_MODE";

    // New Azure specific environment variables
    public static final String ENV_AZURE_SDK_VERSION = "AZURE_SDK_VERSION";
    public static final String ENV_RETRY_COUNT = "AZURE_RETRY_COUNT";
    public static final String ENV_BUFFER_SIZE = "AZURE_BUFFER_SIZE";

    private static final Properties PROPS = loadPropsFromResource();

    private static Properties loadPropsFromResource() {
        Properties prop = new Properties();
        try (InputStream in = ConfigReader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                System.out.println("Resource not found: " + RESOURCE_PATH +
                        " (check if it's in src/main/resources). " +
                        "Falling back to env/defaults.");
            } else {
                prop.load(in);
                System.out.println("Loaded properties from resource: " + RESOURCE_PATH);
            }
        } catch (Exception e) {
            System.out.println("Error reading resource " + RESOURCE_PATH + ": " + e.getMessage());
        }
        return prop;
    }

    private static String getPropOrEnv(String key, String envKey) {
        String val = PROPS.getProperty(key, "");
        if (!val.isEmpty()) {
            return val;
        }
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }
        return "";
    }

    /**
     * Generic property getter that returns a default value if not found
     * @param key The property key
     * @param defaultValue The default value to return if not found
     * @return The property value or default
     */
    public static String getProperty(String key, String defaultValue) {
        String value = PROPS.getProperty(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }

    /**
     * Gets the Azure SDK version to use
     * 'sdk' - Use the Azure Monitor SDK (default)
     * 'direct' - Use direct HTTP posting (more reliable)
     */
    public static String getAzureSdkVersion() {
        String val = getPropOrEnv(KEY_AZURE_SDK_VERSION, ENV_AZURE_SDK_VERSION);
        return val.isEmpty() ? "sdk" : val.toLowerCase();
    }

    /**
     * Gets the retry count for failed exports
     */
    public static int getAzureRetryCount() {
        String val = getPropOrEnv(KEY_RETRY_COUNT, ENV_RETRY_COUNT);
        if (!val.isEmpty()) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException e) {
                System.out.println("Invalid azure.retry.count: " + val + ", using default=3");
            }
        }
        return 3;
    }

    /**
     * Gets the buffer size for batch exporting
     */
    public static int getAzureBufferSize() {
        String val = getPropOrEnv(KEY_BUFFER_SIZE, ENV_BUFFER_SIZE);
        if (!val.isEmpty()) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException e) {
                System.out.println("Invalid azure.buffer.size: " + val + ", using default=100");
            }
        }
        return 100;
    }

    /**
     * Returns true if strict mode is enabled (default: true).
     * In strict mode, only packages that match exactly the patterns in getPackagePrefixes()
     * will be instrumented, and all others will be excluded.
     */
    public static boolean isStrictMode() {
        String val = getPropOrEnv(KEY_STRICT_MODE, ENV_STRICT_MODE);
        // Default to true for safety
        return val.isEmpty() || Boolean.parseBoolean(val);
    }

    // Exporter type (jaeger|azure), default = "jaeger"
    public static String getExporterType() {
        String val = getPropOrEnv(KEY_EXPORTER_TYPE, ENV_EXPORTER);
        if (val.isEmpty()) {
            val = "jaeger";
        }
        return val.toLowerCase();
    }

    public static String getJaegerEndpoint() {
        String val = getPropOrEnv(KEY_JAEGER_ENDPOINT, ENV_JAEGER_ENDPOINT);
        if (val.isEmpty()) {
            val = "http://localhost:14250";
        }
        return val;
    }

    public static String getAzureConnectionString() {
        return getPropOrEnv(KEY_AZURE_CONNSTRING, ENV_AZURE_CONN);
    }

    public static String getSpanProcessorType() {
        String val = getPropOrEnv(KEY_SPAN_PROCESSOR, ENV_SPAN_PROCESSOR);
        if (val.isEmpty()) {
            val = "batch";
        }
        return val.toLowerCase();
    }

    public static double getSamplerRatio() {
        String val = getPropOrEnv(KEY_SAMPLER_RATIO, ENV_SAMPLER_RATIO);
        if (!val.isEmpty()) {
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException e) {
                System.out.println("Invalid sampler.ratio: " + val + ", using default=1.0");
            }
        }
        return 1.0;
    }

    // packages to include
    public static List<String> getPackagePrefixes() {
        String raw = getPropOrEnv(KEY_PACKAGES, ENV_PACKAGES);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        // default - only instrument com.myorg
        return Collections.singletonList("com.myorg");
    }

    public static List<String> getMethodIncludes() {
        String raw = getPropOrEnv(KEY_METHODS_INCLUDE, ENV_METHODS_INCLUDE);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        return Collections.emptyList(); // => any
    }

    public static List<String> getMethodExcludes() {
        String raw = getPropOrEnv(KEY_METHODS_EXCLUDE, ENV_METHODS_EXCLUDE);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        return Collections.emptyList();
    }

    /**
     * Returns packages to exclude from instrumentation.
     * Always excludes critical system packages to avoid classloading issues.
     */
    public static List<String> getPackageExcludes() {
        List<String> excludes = new ArrayList<>();

        // Always exclude these system/framework packages
        // JDK classes
        excludes.add("java.");
        excludes.add("javax.");
        excludes.add("sun.");
        excludes.add("com.sun.");
        excludes.add("jdk.");

        // Logging frameworks
        excludes.add("org.jboss.logmanager");
        excludes.add("org.slf4j");
        excludes.add("org.apache.log4j");
        excludes.add("ch.qos.logback");
        excludes.add("org.apache.logging");

        // Web frameworks
        excludes.add("org.apache.cxf");
        excludes.add("org.springframework");
        excludes.add("org.glassfish");
        excludes.add("jakarta.");

        // Application servers
        excludes.add("org.jboss.");
        excludes.add("org.apache.tomcat");
        excludes.add("org.eclipse.jetty");
        excludes.add("io.undertow");

        // Databases and persistence
        excludes.add("org.hibernate");
        excludes.add("com.mysql");
        excludes.add("oracle.");
        excludes.add("com.zaxxer.hikari");

        // Utility libraries
        excludes.add("com.google.");
        excludes.add("org.apache.commons");
        excludes.add("org.yaml");
        excludes.add("com.fasterxml.jackson");

        // Instrumentation and monitoring libraries
        excludes.add("io.opentelemetry");
        excludes.add("io.micrometer");
        excludes.add("net.bytebuddy");

        // Cloud libraries
        excludes.add("com.azure.");
        excludes.add("com.amazonaws");
        excludes.add("io.vertx");

        // Add user-configured exclusions
        String raw = getPropOrEnv(KEY_PACKAGES_EXCLUDE, ENV_PACKAGES_EXCLUDE);
        if (!raw.isEmpty()) {
            excludes.addAll(Arrays.asList(raw.split(",")));
        }

        // Important: if in strict mode, we'll only be instrumenting exact
        // packages in getPackagePrefixes(), but we still want to keep these exclusions
        // as an extra safety measure

        return excludes;
    }
}