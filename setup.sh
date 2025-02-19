#!/usr/bin/env bash
set -e

###############################################################################
# setup.sh
# Creates a multi-module Maven project to demonstrate:
# 1) A custom ByteBuddy+OTel agent that can run alongside the official OTel agent
# 2) A sample app
# 3) Performance tests (JMH) measuring overhead with or without the agents
###############################################################################

PROJECT_VERSION="1.0.0"
OTEL_VERSION="1.28.0"
BYTEBUDDY_VERSION="1.14.6"

mkdir -p otel-agent-with-custom
cd otel-agent-with-custom

#######################################
# Root POM
#######################################
cat <<EOF > pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.myorg</groupId>
  <artifactId>otel-agent-with-custom-root</artifactId>
  <version>${PROJECT_VERSION}</version>
  <packaging>pom</packaging>

  <modules>
    <module>my-standalone-agent</module>
    <module>sample-app</module>
    <module>performance-tests</module>
  </modules>
</project>
EOF

#######################################
# 1) my-standalone-agent
#######################################
mkdir -p my-standalone-agent/src/main/java/com/myorg/agent
mkdir -p my-standalone-agent/src/main/java/com/myorg/agent/bytebuddy
mkdir -p my-standalone-agent/src/main/java/com/myorg/agent/util
mkdir -p my-standalone-agent/src/main/resources

cat <<EOF > my-standalone-agent/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.myorg</groupId>
    <artifactId>otel-agent-with-custom-root</artifactId>
    <version>${PROJECT_VERSION}</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>my-standalone-agent</artifactId>
  <packaging>jar</packaging>
  <version>${PROJECT_VERSION}</version>
  <name>my-standalone-agent</name>

  <properties>
    <otel.version>${OTEL_VERSION}</otel.version>
    <bytebuddy.version>${BYTEBUDDY_VERSION}</bytebuddy.version>
  </properties>

  <dependencies>
    <!-- ByteBuddy for instrumentation -->
    <dependency>
      <groupId>net.bytebuddy</groupId>
      <artifactId>byte-buddy</artifactId>
      <version>\${bytebuddy.version}</version>
    </dependency>
    <dependency>
      <groupId>net.bytebuddy</groupId>
      <artifactId>byte-buddy-agent</artifactId>
      <version>\${bytebuddy.version}</version>
    </dependency>

    <!-- OTel API + SDK + Logging exporter -->
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-api</artifactId>
      <version>\${otel.version}</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-context</artifactId>
      <version>\${otel.version}</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk</artifactId>
      <version>\${otel.version}</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-logging</artifactId>
      <version>\${otel.version}</version>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk-metrics</artifactId>
      <version>\${otel.version}</version>
    </dependency>

    <!-- W3C trace context is in 'io.opentelemetry.api.trace.propagation' for 1.28+ -->
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-api</artifactId>
      <version>\${otel.version}</version>
    </dependency>

    <!-- For reading config from .properties or env var -->
    <dependency>
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-configuration2</artifactId>
      <version>2.8.0</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- Shade plugin to create a fat jar with a Premain-Class -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.4.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <manifestEntries>
                    <Premain-Class>com.myorg.agent.OtelByteBuddyAgent</Premain-Class>
                  </manifestEntries>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
EOF

cat <<EOF > my-standalone-agent/src/main/java/com/myorg/agent/OtelByteBuddyAgent.java
package com.myorg.agent;

import com.myorg.agent.bytebuddy.MethodInstrumentAdvisor;
import com.myorg.agent.bytebuddy.SystemMetrics;
import com.myorg.agent.util.ConfigReader;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;

import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Our custom ByteBuddy + OTel SDK agent.
 * This can run alongside the official OTel agent by doing:
 *   java -javaagent:opentelemetry-javaagent.jar -javaagent:my-standalone-agent.jar ...
 */
public class OtelByteBuddyAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        // read packages from config
        List<String> packages = ConfigReader.getPackagePrefixes();
        System.out.println("[OtelByteBuddyAgent] Packages to instrument: " + packages);

        setupOpenTelemetry();

        // Install ByteBuddy instrumentation
        MethodInstrumentAdvisor.install(inst, packages);

        System.out.println("[OtelByteBuddyAgent] Agent installed. ByteBuddy + OTel SDK approach.");
    }

    private static void setupOpenTelemetry() {
        // A Resource describing the "service"
        Resource resource = Resource.builder()
            .put("service.name", "CustomByteBuddyAgentService")
            .build();

        // TracerProvider (logs spans with LoggingSpanExporter)
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(SimpleSpanProcessor.create(new LoggingSpanExporter()))
            .build();

        // MeterProvider (logs metrics every 5 seconds)
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                PeriodicMetricReader.builder(LoggingMetricExporter.create())
                    .setInterval(5, TimeUnit.SECONDS)
                    .build()
            )
            .build();

        // Build OTel with W3C context
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();

        GlobalOpenTelemetry.set(sdk);

        // On shutdown, close
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            tracerProvider.shutdown();
            meterProvider.shutdown();
        }));

        // Start system metrics
        SystemMetrics.registerGauges();
    }
}
EOF

cat <<EOF > my-standalone-agent/src/main/java/com/myorg/agent/bytebuddy/MethodInstrumentAdvisor.java
package com.myorg.agent.bytebuddy;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import java.lang.instrument.Instrumentation;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Applies ByteBuddy instrumentation to methods named "processOrder" or "subProcess"
 * in user-defined packages (from config).
 */
public class MethodInstrumentAdvisor {

    public static void install(Instrumentation inst, List<String> packagePrefixes) {
        AgentBuilder builder = new AgentBuilder.Default();

        for (String prefix : packagePrefixes) {
            builder = builder.type(nameStartsWith(prefix))
                .transform((b, td, cl, module, pd) ->
                    b.method(namedOneOf("processOrder","subProcess"))
                     .intercept(Advice.to(ProcessOrderAdvice.class))
                );
        }

        builder.installOn(inst);
    }
}
EOF

cat <<EOF > my-standalone-agent/src/main/java/com/myorg/agent/bytebuddy/ProcessOrderAdvice.java
package com.myorg.agent.bytebuddy;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Intercepts processOrder(...) & subProcess(...) calls
 * Demonstrates parent->child or sequential spans in logs.
 */
public class ProcessOrderAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Span onEnter(
        @Advice.Origin("#t.#m") String methodName,
        @Advice.AllArguments Object[] args,
        @Advice.Local("span") Span span
    ) {
        String info = (args.length > 0) ? String.valueOf(args[0]) : "no-arg";
        Tracer tracer = GlobalOpenTelemetry.getTracer("my-custom-agent-tracer");
        span = tracer.spanBuilder(methodName).startSpan();
        span.setAttribute("info", info);

        System.out.println("[ProcessOrderAdvice] Starting span: " + span.getSpanContext().getSpanId()
            + " method=" + methodName);
        return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Local("span") Span span,
        @Advice.Thrown Throwable thrown
    ) {
        if (thrown != null) {
            span.recordException(thrown);
        }
        span.end();
        System.out.println("[ProcessOrderAdvice] Ended span: " + span.getSpanContext().getSpanId());
    }
}
EOF

cat <<EOF > my-standalone-agent/src/main/java/com/myorg/agent/bytebuddy/SystemMetrics.java
package com.myorg.agent.bytebuddy;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.common.Attributes;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

public class SystemMetrics {

    private static boolean registered = false;

    public static void registerGauges() {
        if (registered) return;
        registered = true;

        Meter meter = GlobalOpenTelemetry.getMeter("my-custom-agent-meter");
        OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        // CPU load
        meter.gaugeBuilder("system.process.cpu.load")
            .setDescription("Process CPU load (0..1)")
            .buildWithCallback(obs -> {
                double val = osBean.getProcessCpuLoad();
                if (val < 0) val = 0.0;
                obs.record(val, Attributes.empty());
            });

        // Memory usage
        meter.gaugeBuilder("runtime.memory.used")
            .setDescription("Used memory in bytes")
            .buildWithCallback(obs -> {
                long totalMem = Runtime.getRuntime().totalMemory();
                long freeMem = Runtime.getRuntime().freeMemory();
                obs.record((double)(totalMem - freeMem), Attributes.empty());
            });
    }
}
EOF

cat <<EOF > my-standalone-agent/src/main/java/com/myorg/agent/util/ConfigReader.java
package com.myorg.agent.util;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Reads package prefixes from my-agent-config.properties or ENV
 */
public class ConfigReader {

    private static final String PROP_FILE = "my-agent-config.properties";
    private static final String KEY_PACKAGES = "instrument.packages";
    private static final String ENV_VAR = "INSTRUMENT_PACKAGES";

    public static List<String> getPackagePrefixes() {
        try {
            Configurations configs = new Configurations();
            File f = new File(PROP_FILE);
            if (f.exists()) {
                Configuration c = configs.properties(f);
                String raw = c.getString(KEY_PACKAGES, "com.myorg.app");
                return Arrays.asList(raw.split(","));
            }
        } catch (Exception e) {
            // ignore
        }

        String envVal = System.getenv(ENV_VAR);
        if (envVal != null && !envVal.isBlank()) {
            return Arrays.asList(envVal.split(","));
        }
        return Arrays.asList("com.myorg.app");
    }
}
EOF

# sample config
cat <<EOF > my-standalone-agent/src/main/resources/my-agent-config.properties
instrument.packages=com.myorg.app
EOF


#######################################
# 2) sample-app
#######################################
mkdir -p sample-app/src/main/java/com/myorg/app
cat <<EOF > sample-app/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.myorg</groupId>
    <artifactId>otel-agent-with-custom-root</artifactId>
    <version>${PROJECT_VERSION}</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>sample-app</artifactId>
  <version>${PROJECT_VERSION}</version>
  <packaging>jar</packaging>
  <name>sample-app</name>

  <build>
    <plugins>
      <!-- main manifest so we can run `java -jar sample-app.jar` -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <version>3.2.2</version>
        <configuration>
          <archive>
            <manifest>
              <mainClass>com.myorg.app.SampleApp</mainClass>
            </manifest>
          </archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
EOF

cat <<EOF > sample-app/src/main/java/com/myorg/app/OrderService.java
package com.myorg.app;

/**
 * Two methods: processOrder -> subProcess
 * So we see parent->child or sequential spans.
 */
public class OrderService {
    public void processOrder(String orderId) {
        System.out.println("[OrderService] Processing order: " + orderId);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subProcess(orderId);
    }

    public void subProcess(String orderId) {
        System.out.println("[OrderService] subProcess for order: " + orderId);
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
EOF

cat <<EOF > sample-app/src/main/java/com/myorg/app/SampleApp.java
package com.myorg.app;

public class SampleApp {
    public static void main(String[] args) {
        OrderService svc = new OrderService();
        svc.processOrder("A-123");
        svc.processOrder("B-456");
        System.out.println("[SampleApp] Done.");
    }
}
EOF

#######################################
# 3) performance-tests (JMH)
#######################################
mkdir -p performance-tests/src/main/java/com/myorg/benchmarks
cat <<EOF > performance-tests/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.myorg</groupId>
    <artifactId>otel-agent-with-custom-root</artifactId>
    <version>${PROJECT_VERSION}</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>performance-tests</artifactId>
  <version>${PROJECT_VERSION}</version>
  <packaging>jar</packaging>
  <name>performance-tests</name>

  <dependencies>
    <!-- JMH Core -->
    <dependency>
      <groupId>org.openjdk.jmh</groupId>
      <artifactId>jmh-core</artifactId>
      <version>1.36</version>
    </dependency>
    <!-- JMH generator -->
    <dependency>
      <groupId>org.openjdk.jmh</groupId>
      <artifactId>jmh-generator-annprocess</artifactId>
      <version>1.36</version>
      <scope>provided</scope>
    </dependency>

    <!-- We'll benchmark the sample-app code -->
    <dependency>
      <groupId>com.myorg</groupId>
      <artifactId>sample-app</artifactId>
      <version>${PROJECT_VERSION}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- shade plugin to produce an executable jar with JMH main -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.4.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>shade</goal>
            </goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>org.openjdk.jmh.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
EOF

cat <<EOF > performance-tests/src/main/java/com/myorg/benchmarks/OrderServiceBenchmark.java
package com.myorg.benchmarks;

import com.myorg.app.OrderService;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark "processOrder" calls.
 * We'll measure throughput or average time.
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@Fork(1)
@OutputTimeUnit(TimeUnit.SECONDS)
public class OrderServiceBenchmark {

    private OrderService svc;

    @Setup
    public void setup() {
        svc = new OrderService();
    }

    @Benchmark
    public void benchmarkProcessOrder() {
        svc.processOrder("BENCH-ORDER");
    }
}
EOF

#######################################
# run-benchmarks.sh
#######################################
cat <<EOF > run-benchmarks.sh
#!/usr/bin/env bash
set -e

# build everything
mvn clean package -DskipTests

AGENT_JAR="my-standalone-agent/target/my-standalone-agent-${PROJECT_VERSION}.jar"
JMH_JAR="performance-tests/target/performance-tests-${PROJECT_VERSION}.jar"

if [ ! -f "\$AGENT_JAR" ]; then
  echo "[ERROR] Agent jar not found: \$AGENT_JAR"
  exit 1
fi
if [ ! -f "\$JMH_JAR" ]; then
  echo "[ERROR] JMH jar not found: \$JMH_JAR"
  exit 1
fi

echo
echo "========================================================="
echo "Benchmark WITHOUT any agent..."
echo "========================================================="
java -jar "\$JMH_JAR" \
  -wi 2 -i 5 \
  -f 1 \
  -bm thrpt \
  -rff no_agent.json \
  > no_agent.txt

echo
echo "[INFO] Results (no_agent.txt):"
cat no_agent.txt

echo
echo "========================================================="
echo "Benchmark WITH custom agent only..."
echo "========================================================="
java -javaagent:"\$AGENT_JAR" \
  -jar "\$JMH_JAR" \
  -wi 2 -i 5 \
  -f 1 \
  -bm thrpt \
  -rff custom_agent.json \
  > custom_agent.txt

echo
echo "[INFO] Results (custom_agent.txt):"
cat custom_agent.txt

echo
echo "========================================================="
echo "Benchmark WITH official OTel agent only (assuming you have it downloaded)."
echo "Set the path to your 'opentelemetry-javaagent.jar' via OTEL_JAVAAGENT env var."
echo "========================================================="
if [ -z "\$OTEL_JAVAAGENT" ]; then
  echo "[WARNING] OTEL_JAVAAGENT env var not set. Skipping official agent test."
else
  if [ ! -f "\$OTEL_JAVAAGENT" ]; then
    echo "[ERROR] OTEL_JAVAAGENT at '\$OTEL_JAVAAGENT' not found!"
  else
    java -javaagent:"\$OTEL_JAVAAGENT" \
      -jar "\$JMH_JAR" \
      -wi 2 -i 5 \
      -f 1 \
      -bm thrpt \
      -rff official_otel.json \
      > official_otel.txt

    echo
    echo "[INFO] Results (official_otel.txt):"
    cat official_otel.txt
  fi
fi

echo
echo "========================================================="
echo "Benchmark WITH BOTH agents (chained)."
echo "========================================================="
if [ -z "\$OTEL_JAVAAGENT" ]; then
  echo "[WARNING] OTEL_JAVAAGENT not set, can't do both-agent test."
else
  if [ ! -f "\$OTEL_JAVAAGENT" ]; then
    echo "[ERROR] OTEL_JAVAAGENT at '\$OTEL_JAVAAGENT' not found!"
  else
    java -javaagent:"\$OTEL_JAVAAGENT" -javaagent:"\$AGENT_JAR" \
      -jar "\$JMH_JAR" \
      -wi 2 -i 5 \
      -f 1 \
      -bm thrpt \
      -rff both_agents.json \
      > both_agents.txt

    echo
    echo "[INFO] Results (both_agents.txt):"
    cat both_agents.txt
  fi
fi

echo
echo "========================================================="
echo "[INFO] Compare .txt files to see overhead differences."
echo "========================================================="
EOF
chmod +x run-benchmarks.sh

#######################################
# README.md
#######################################
cat <<EOF > README.md
# Custom ByteBuddy Agent + Official OTel Agent Example

This project demonstrates:

1. A **custom ByteBuddy + OTel SDK agent** (\`my-standalone-agent\`) that can run **alongside** the official OTel Java agent (\`opentelemetry-javaagent.jar\`).
2. A **sample app** (\`sample-app\`) calling \`OrderService.processOrder(...)\`.
3. **Performance tests** (\`performance-tests\`) using JMH to measure overhead with various agent combinations.

## Modules

- **my-standalone-agent/**:  
  - A jar with a \`Premain-Class\` that uses ByteBuddy to intercept \`processOrder\` and \`subProcess\`.  
  - Uses the OTel SDK (logging exporter) for spans + metrics.  
  - Reads packages to instrument from \`my-agent-config.properties\` or env var \`INSTRUMENT_PACKAGES\`.  
  - Uses **W3C trace** context.  

- **sample-app/**:  
  - A trivial app: \`OrderService\` with \`processOrder(...) -> subProcess(...)\`  
  - A \`SampleApp\` main method.  

- **performance-tests/**:  
  - JMH benchmarks to measure overhead of \`OrderService.processOrder\`.  
  - You can run with or without any agent(s).

## Building

\`\`\`
mvn clean package
\`\`\`

## Running the Sample App

- **Without** any agent:
  \`\`\`
  java -jar sample-app/target/sample-app-1.0.0.jar
  \`\`\`

- **With** the **custom** agent:
  \`\`\`
  java \\
    -javaagent:my-standalone-agent/target/my-standalone-agent-1.0.0.jar \\
    -jar sample-app/target/sample-app-1.0.0.jar
  \`\`\`

- **With** the **official** agent AND the custom agent:
  \`\`\`
  java \\
    -javaagent:opentelemetry-javaagent.jar \\
    -javaagent:my-standalone-agent/target/my-standalone-agent-1.0.0.jar \\
    -jar sample-app/target/sample-app-1.0.0.jar
  \`\`\`
  You must download the official agent from
  [OTel Java Instrumentation Releases](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases).

## Benchmarking Overhead

Use \`run-benchmarks.sh\`. It runs JMH in multiple scenarios:

- **No agent**  
- **Custom agent only**  
- **Official agent only** (if you set \`OTEL_JAVAAGENT\` env var to point to \`opentelemetry-javaagent.jar\`)  
- **Both** agents chained

\`\`\`
./run-benchmarks.sh
\`\`\`

You’ll get output files: \`no_agent.txt\`, \`custom_agent.txt\`, \`official_otel.txt\`, \`both_agents.txt\`.

Compare the throughput/latency to see extra CPU/memory usage or overhead.

## FAQ

1. **Where’s the traceId?**  
   In logs from \`LoggingSpanExporter\`, the first 32-hex string after the colon is your trace ID, e.g.
   \`\`\`
   INFO: 'com.myorg.app.OrderService.subProcess' : 0123456789abcdef0123456789abcdef abcdabcdabcdabcd ...
   \`\`\`
   The **first** is **trace ID** (32 hex digits). The second is **span ID** (16 hex digits).

2. **Combining both agents**  
   If they both instrument the same classes, you may see double instrumentation. Usually, you want each agent to handle different sets of packages or instrumentation logic.

3. **my-agent-config.properties**  
   You can specify \`instrument.packages=com.myorg.app\` (comma-separated). Or use \`INSTRUMENT_PACKAGES\` env var.

That’s it! You have a **production** custom agent coexisting with the **official** agent, plus a **benchmark** that measures overhead in CPU, memory, and latency metrics (via JMH + OTel logs).
EOF

echo
echo "[DONE] Setup script complete!"
echo "Project created in:"
pwd
echo
echo "Next steps:"
echo "1) cd otel-agent-with-custom"
echo "2) ./run-benchmarks.sh"
echo "   - This builds everything, runs JMH in multiple scenarios if you supply the official agent in OTEL_JAVAAGENT."
echo
echo "Read README.md for more usage details."
