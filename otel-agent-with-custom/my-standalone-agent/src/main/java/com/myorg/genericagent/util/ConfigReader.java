package com.myorg.genericagent.util;

import java.io.InputStream;
import java.util.*;
//import java.util.logging.Logger;

/**
 * Reads "my-agent-config.properties" from resources (or environment),
 * providing config for:
 *   1) instrumentation (packages, includes, excludes)
 *   2) exporter.type (jaeger|azure)
 *   3) jaeger.endpoint
 *   4) azure.connectionString
 *   5) span.processor (batch|simple)
 *   6) sampler.ratio (0..1)
 *
 * Falls back to env vars if not in the resource file.
 */
public class ConfigReader {
  //  private static final Logger logger = Logger.getLogger(ConfigReader.class.getName());

    private static final String RESOURCE_PATH = "/my-agent-config.properties";

    // Key definitions
    public static final String KEY_EXPORTER_TYPE = "exporter.type"; // "jaeger" or "azure"
    public static final String KEY_JAEGER_ENDPOINT = "jaeger.endpoint";
    public static final String KEY_AZURE_CONNSTRING = "azure.connectionString";
    public static final String KEY_SPAN_PROCESSOR = "span.processor"; // "batch" or "simple"
    public static final String KEY_SAMPLER_RATIO = "sampler.ratio"; // e.g. "0.2"

    // Instrumentation config keys
    private static final String KEY_PACKAGES = "instrument.packages";
    private static final String KEY_METHODS_INCLUDE = "instrument.includeMethods";
    private static final String KEY_METHODS_EXCLUDE = "instrument.excludeMethods";

    // Env variable fallbacks
    private static final String ENV_EXPORTER = "OTEL_EXPORTER";
    private static final String ENV_JAEGER_ENDPOINT = "JAEGER_ENDPOINT";
    private static final String ENV_AZURE_CONN = "AZURE_CONNECTION_STRING";
    private static final String ENV_SPAN_PROCESSOR = "OTEL_SPANPROCESSOR";
    private static final String ENV_SAMPLER_RATIO = "OTEL_SAMPLER_RATIO";

    private static final String ENV_PACKAGES = "INSTRUMENT_PACKAGES";
    private static final String ENV_METHODS_INCLUDE = "INSTRUMENT_INCLUDE_METHODS";
    private static final String ENV_METHODS_EXCLUDE = "INSTRUMENT_EXCLUDE_METHODS";

    // We'll load once from resource
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

    /** e.g. "jaeger" or "azure" */
    public static String getExporterType() {
        String val = getPropOrEnv(KEY_EXPORTER_TYPE, ENV_EXPORTER);
        if (val.isEmpty()) {
            val = "jaeger"; // default
        }
        return val.toLowerCase();
    }

    /** e.g. "http://localhost:14250" */
    public static String getJaegerEndpoint() {
        String val = getPropOrEnv(KEY_JAEGER_ENDPOINT, ENV_JAEGER_ENDPOINT);
        if (val.isEmpty()) {
            val = "http://localhost:14250"; // default
        }
        return val;
    }

    /** e.g. "InstrumentationKey=xyz;IngestionEndpoint=..." */
    public static String getAzureConnectionString() {
        return getPropOrEnv(KEY_AZURE_CONNSTRING, ENV_AZURE_CONN);
    }

    /** "batch" or "simple", default= "batch" */
    public static String getSpanProcessorType() {
        String val = getPropOrEnv(KEY_SPAN_PROCESSOR, ENV_SPAN_PROCESSOR);
        if (val.isEmpty()) {
            val = "batch";
        }
        return val.toLowerCase();
    }

    /** Sampler ratio, e.g. 0.2 => 20%. default=1.0 => always sample. */
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

    // =========== instrumentation includes/excludes ============

    public static List<String> getPackagePrefixes() {
        String raw = getPropOrEnv(KEY_PACKAGES, ENV_PACKAGES);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        return Collections.singletonList("com.myorg"); // default
    }

    public static List<String> getMethodIncludes() {
        String raw = getPropOrEnv(KEY_METHODS_INCLUDE, ENV_METHODS_INCLUDE);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        return Collections.emptyList(); // default => any
    }

    public static List<String> getMethodExcludes() {
        String raw = getPropOrEnv(KEY_METHODS_EXCLUDE, ENV_METHODS_EXCLUDE);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        return Collections.emptyList(); // default => none
    }
}
