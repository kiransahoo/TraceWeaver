# Custom ByteBuddy Agent + Official OTel Agent Example

This project demonstrates:

1. A **custom ByteBuddy + OTel SDK agent** (`my-standalone-agent`) that can run **alongside** the official OTel Java agent (`opentelemetry-javaagent.jar`).
2. A **sample app** (`sample-app`) calling `OrderService.processOrder(...)`.
3. **Performance tests** (`performance-tests`) using JMH to measure overhead with various agent combinations.

## Modules

- **my-standalone-agent/**:  
  - A jar with a `Premain-Class` that uses ByteBuddy to intercept `processOrder` and `subProcess`.  
  - Uses the OTel SDK (logging exporter) for spans + metrics.  
  - Reads packages to instrument from `my-agent-config.properties` or env var `INSTRUMENT_PACKAGES`.  
  - Uses **W3C trace** context.  

- **sample-app/**:  
  - A trivial app: `OrderService` with `processOrder(...) -> subProcess(...)`  
  - A `SampleApp` main method.  

- **performance-tests/**:  
  - JMH benchmarks to measure overhead of `OrderService.processOrder`.  
  - You can run with or without any agent(s).

## Building

```
mvn clean package
```

## Running the Sample App

- **Without** any agent:
  ```
  java -jar sample-app/target/sample-app-1.0.0.jar
  ```

- **With** the **custom** agent:
  ```
  java \
    -javaagent:my-standalone-agent/target/my-standalone-agent-1.0.0.jar \
    -jar sample-app/target/sample-app-1.0.0.jar
  ```

- **With** the **official** agent AND the custom agent:
  ```
  java \
    -javaagent:opentelemetry-javaagent.jar \
    -javaagent:my-standalone-agent/target/my-standalone-agent-1.0.0.jar \
    -jar sample-app/target/sample-app-1.0.0.jar
  ```
  You must download the official agent from
  [OTel Java Instrumentation Releases](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases).

## Benchmarking Overhead

Use `run-benchmarks.sh`. It runs JMH in multiple scenarios:

- **No agent**  
- **Custom agent only**  
- **Official agent only** (if you set `OTEL_JAVAAGENT` env var to point to `opentelemetry-javaagent.jar`)  
- **Both** agents chained

```
./run-benchmarks.sh
```

You’ll get output files: `no_agent.txt`, `custom_agent.txt`, `official_otel.txt`, `both_agents.txt`.

Compare the throughput/latency to see extra CPU/memory usage or overhead.

## FAQ

1. **Where’s the traceId?**  
   In logs from `LoggingSpanExporter`, the first 32-hex string after the colon is your trace ID, e.g.
   ```
   INFO: 'com.myorg.app.OrderService.subProcess' : 0123456789abcdef0123456789abcdef abcdabcdabcdabcd ...
   ```
   The **first** is **trace ID** (32 hex digits). The second is **span ID** (16 hex digits).

2. **Combining both agents**  
   If they both instrument the same classes, you may see double instrumentation. Usually, you want each agent to handle different sets of packages or instrumentation logic.

3. **my-agent-config.properties**  
   You can specify `instrument.packages=com.myorg.app` (comma-separated). Or use `INSTRUMENT_PACKAGES` env var.

That’s it! You have a **production** custom agent coexisting with the **official** agent, plus a **benchmark** that measures overhead in CPU, memory, and latency metrics (via JMH + OTel logs).
