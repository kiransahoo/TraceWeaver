package com.tracer.genericagent.util;
import java.io.*;
import java.util.*;

/**
 * Configuration reader that loads from multiple sources in order of priority:
 * 1. External file specified by system property "tracer.config.path"
 * 2. Standard locations (/etc/tracer/agent-config.properties, ./agent-config.properties)
 * 3. Environment variables
 * 4. Built-in resource "/my-agent-config.properties"
 * 5. Default values
 * @author kiransahoo
 */
public class ConfigReader {

    private static final String RESOURCE_PATH = "/my-agent-config.properties";
    private static final String[] STANDARD_CONFIG_PATHS = {
            "./agent-config.properties",                      // Current directory
            System.getProperty("user.home") + "/.tracer/agent-config.properties", // User home dir
            "/etc/tracer/agent-config.properties",             // System-wide config
            "/opt/app/config/agent-config.properties"          // Docker-friendly location
    };

    // System property that can specify external config location
    private static final String CONFIG_PATH_PROPERTY = "tracer.config.path";

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

    //  Azure specific configuration keys
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

    //  Azure specific environment variables
    public static final String ENV_AZURE_SDK_VERSION = "AZURE_SDK_VERSION";
    public static final String ENV_RETRY_COUNT = "AZURE_RETRY_COUNT";
    public static final String ENV_BUFFER_SIZE = "AZURE_BUFFER_SIZE";

    //  SLA-related configuration keys
    private static final String KEY_SLA_ENABLED = "sla.enabled";
    private static final String KEY_SLA_THRESHOLD_MS = "sla.threshold.ms";
    private static final String KEY_SLA_ALWAYS_CAPTURE_EXCEPTIONS = "sla.captureExceptions";

    // Environment variable fallbacks
    private static final String ENV_SLA_ENABLED = "TRACE_SLA_ENABLED";
    private static final String ENV_SLA_THRESHOLD_MS = "TRACE_SLA_THRESHOLD_MS";
    private static final String ENV_SLA_CAPTURE_EXCEPTIONS = "TRACE_CAPTURE_EXCEPTIONS";


    // Properties object holding the merged configuration
    private static final Properties PROPS = loadConfiguration();

    // Tracks which config source was used (for diagnostics)
    private static String configSource = "defaults";
    private static final String AGENT_CONFIG_FILE_PROPERTY = "agent.config.file";

    /**
     * Load configuration from all possible sources in priority order
     */
    private static Properties loadConfiguration() {
        Properties props = new Properties();

        // Try to load from external file specified by agent.config.file property (add this block)
        String agentConfigPath = System.getProperty(AGENT_CONFIG_FILE_PROPERTY);
        if (agentConfigPath != null && !agentConfigPath.isEmpty()) {
            if (loadPropsFromFile(props, agentConfigPath)) {
                configSource = "system property: " + agentConfigPath;
                System.out.println("[ConfigReader] Loaded configuration from " + agentConfigPath);
                return props;
            }
        }
        // Try to load from external file specified by system property
        String configPath = System.getProperty(CONFIG_PATH_PROPERTY);
        if (configPath != null && !configPath.isEmpty()) {
            if (loadPropsFromFile(props, configPath)) {
                configSource = "system property: " + configPath;
                System.out.println("[ConfigReader] Loaded configuration from " + configPath);
                return props;
            }
        }

        // Try standard config locations
        for (String path : STANDARD_CONFIG_PATHS) {
            if (loadPropsFromFile(props, path)) {
                configSource = "standard location: " + path;
                System.out.println("[ConfigReader] Loaded configuration from " + path);
                return props;
            }
        }

        // Fall back to resource file
        try (InputStream in = ConfigReader.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                props.load(in);
                configSource = "built-in resource: " + RESOURCE_PATH;
                System.out.println("[ConfigReader] Loaded configuration from built-in resource");
                return props;
            }
        } catch (Exception e) {
            System.out.println("[ConfigReader] Error reading resource: " + e.getMessage());
        }

        // No config file found - we'll rely on environment variables and defaults
        configSource = "environment variables and defaults";
        System.out.println("[ConfigReader] No configuration file found, using environment variables and defaults");
        return props;
    }

    /**
     * Load properties from a file path
     */
    private static boolean loadPropsFromFile(Properties props, String path) {
        try {
            File file = new File(path);
            if (file.exists() && file.canRead()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("[ConfigReader] Error reading " + path + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Get property from loaded properties or environment
     */
    private static String getPropOrEnv(String key, String envKey) {
        // First check properties
        String val = PROPS.getProperty(key, "");
        if (!val.isEmpty()) {
            return val;
        }

        // Then check environment
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }

        return "";
    }

    /**
     * Returns the configuration source that was used
     */
    public static String getConfigSource() {
        return configSource;
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

    // The rest of the class remains the same with all the getters...

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

        return excludes;
    }

    /**
     * Check if SLA-based filtering is enabled
     */
    public static boolean isSlaEnabled() {
        String val = getPropOrEnv(KEY_SLA_ENABLED, ENV_SLA_ENABLED);
        return val.isEmpty() ? false : Boolean.parseBoolean(val);
    }



    /**
     * Check if exceptions should always be captured, regardless of duration
     */
    public static boolean shouldCaptureExceptions() {
        String val = getPropOrEnv(KEY_SLA_ALWAYS_CAPTURE_EXCEPTIONS, ENV_SLA_CAPTURE_EXCEPTIONS);
        return val.isEmpty() ? true : Boolean.parseBoolean(val);
    }

    /**
     * Check if SLA-based filtering is enabled
     */
    public static boolean isSlaFilteringEnabled() {
        String val = getPropOrEnv("sla.filter.enabled", "TRACE_SLA_FILTER_ENABLED");
        return val.isEmpty() ? false : Boolean.parseBoolean(val);
    }

    /**
     * Get the SLA threshold in milliseconds
     */
    public static long getSlaThresholdMs() {
        String val = getPropOrEnv("sla.threshold.ms", "TRACE_SLA_THRESHOLD_MS");
        if (!val.isEmpty()) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException e) {
                System.out.println("Invalid sla.threshold.ms: " + val + ", using default=500");
            }
        }
        return 500; // Default 500ms
    }

    /**
     * Check if exception-based filtering is enabled
     */
    public static boolean isExceptionFilteringEnabled() {
        String val = getPropOrEnv("exception.filter.enabled", "TRACE_EXCEPTION_FILTER_ENABLED");
        return val.isEmpty() ? true : Boolean.parseBoolean(val);
    }
}