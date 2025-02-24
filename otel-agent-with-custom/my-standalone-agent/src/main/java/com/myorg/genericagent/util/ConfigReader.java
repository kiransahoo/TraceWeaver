package com.myorg.genericagent.util;

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

    // **NEW** key for excluding packages
    private static final String KEY_PACKAGES_EXCLUDE = "instrument.excludePackages";

    // Env variable fallbacks
    private static final String ENV_EXPORTER = "OTEL_EXPORTER";
    private static final String ENV_JAEGER_ENDPOINT = "JAEGER_ENDPOINT";
    private static final String ENV_AZURE_CONN = "AZURE_CONNECTION_STRING";
    private static final String ENV_SPAN_PROCESSOR = "OTEL_SPANPROCESSOR";
    private static final String ENV_SAMPLER_RATIO = "OTEL_SAMPLER_RATIO";

    private static final String ENV_PACKAGES = "INSTRUMENT_PACKAGES";
    private static final String ENV_METHODS_INCLUDE = "INSTRUMENT_INCLUDE_METHODS";
    private static final String ENV_METHODS_EXCLUDE = "INSTRUMENT_EXCLUDE_METHODS";
    // **NEW** env var for exclude packages
    private static final String ENV_PACKAGES_EXCLUDE = "INSTRUMENT_EXCLUDE_PACKAGES";

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
        // default
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

    // **NEW** packages to exclude
    public static List<String> getPackageExcludes() {
        String raw = getPropOrEnv(KEY_PACKAGES_EXCLUDE, ENV_PACKAGES_EXCLUDE);
        if (!raw.isEmpty()) {
            return Arrays.asList(raw.split(","));
        }
        return Collections.emptyList();
    }
}
