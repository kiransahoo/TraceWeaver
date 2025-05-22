#!/bin/bash
set -e

echo "==== Starting Application Insights Agent + Custom Agent Integration Test (Java 17) ===="

# Define variables
PROJECT_DIR="trace-test"
# Your custom agent path
AGENT_JAR_PATH="/Users/kiransahoo/.m2/repository/com/myorg/trace-standalone-agent/1.0.0/trace-standalone-agent-1.0.0.jar"
SERVER_PORT=6070
INSTRUMENTATION_KEY="fcec979a-1607-4847-a8a1-73645d5ae20e"

# Step 1: Download Application Insights Agent JAR
echo "==== Downloading Application Insights Agent ===="
AI_VERSION="3.1.0"
AI_AGENT_JAR="/Users/kiransahoo/Desktop/code/applicationinsights-agent-$AI_VERSION.jar"

if [ ! -f "$AI_AGENT_JAR" ]; then
    echo "Downloading Application Insights Agent JAR..."
    curl -L "https://github.com/microsoft/ApplicationInsights-Java/releases/download/$AI_VERSION/applicationinsights-agent-$AI_VERSION.jar" -o "$AI_AGENT_JAR"
else
    echo "Application Insights Agent JAR already exists at $AI_AGENT_JAR"
fi

# Step 2: Create test application
echo "==== Creating Test Application ===="
mkdir -p $PROJECT_DIR/src/main/java/com/test
mkdir -p $PROJECT_DIR/src/main/resources

# Create pom.xml WITHOUT Application Insights dependencies
cat > $PROJECT_DIR/pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.5</version>
    </parent>

    <groupId>com.test</groupId>
    <artifactId>trace-test</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Spring Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Add dependency to log requests/responses for debugging -->
        <dependency>
            <groupId>org.apache.httpcomponents</groupId>
            <artifactId>httpclient</artifactId>
            <version>4.5.13</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF

# Create Application.java WITHOUT TelemetryClient references
cat > $PROJECT_DIR/src/main/java/com/test/Application.java << EOF
package com.test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // Print Java version for verification
        System.out.println("Java version: " + System.getProperty("java.version"));
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // Add request/response logging interceptor
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new LoggingInterceptor());
        restTemplate.setInterceptors(interceptors);

        return restTemplate;
    }

    // Interceptor to log HTTP headers for debugging context propagation
    public static class LoggingInterceptor implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution) throws IOException {
            System.out.println("\n=== HTTP Request Headers ===");
            request.getHeaders().forEach((key, value) -> {
                System.out.println(key + ": " + value);
            });

            ClientHttpResponse response = execution.execute(request, body);

            System.out.println("\n=== HTTP Response Headers ===");
            response.getHeaders().forEach((key, value) -> {
                System.out.println(key + ": " + value);
            });

            return response;
        }
    }

    @RestController
    public static class TestController {

        @Autowired
        private RestTemplate restTemplate;

        @GetMapping("/test")
        public String test() {
            System.out.println("\n=== /test endpoint called ===");

            // First level method - should use parent context
            firstLevelMethod();

            try {
                // Give time for first level to be processed
                TimeUnit.SECONDS.sleep(1);

                // Make HTTP call to another endpoint
                // This should propagate trace context via HTTP headers
                System.out.println("\n=== Making HTTP call to /downstream ===");
                ResponseEntity<String> response = restTemplate.getForEntity(
                    "http://localhost:$SERVER_PORT/downstream", String.class);

                System.out.println("=== /test completed with status: " +
                    response.getStatusCode() + ", body: " + response.getBody() + " ===");

                return "Trace test completed: " + response.getBody();
            } catch (Exception e) {
                System.err.println("Error in test endpoint: " + e.getMessage());
                e.printStackTrace();
                return "Error: " + e.getMessage();
            }
        }

        @GetMapping("/downstream")
        public String downstream() {
            System.out.println("\n=== /downstream endpoint called ===");

            // This should continue the same trace from the parent request
            secondLevelMethod();

            System.out.println("=== /downstream completed ===");
            return "Downstream processed";
        }

        private void firstLevelMethod() {
            System.out.println("=== firstLevelMethod called ===");

            // Traced method - should be captured by your agent
            try {
                TimeUnit.MILLISECONDS.sleep(500);
                System.out.println("First level method completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void secondLevelMethod() {
            System.out.println("=== secondLevelMethod called ===");

            // Traced method - should be captured by your agent
            try {
                TimeUnit.MILLISECONDS.sleep(500);
                System.out.println("Second level method completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
EOF

# Replace SERVER_PORT placeholder with actual port
sed -i "" "s/\$SERVER_PORT/$SERVER_PORT/g" $PROJECT_DIR/src/main/java/com/test/Application.java

# Create application.properties
cat > $PROJECT_DIR/src/main/resources/application.properties << EOF
# Custom port
server.port=$SERVER_PORT
EOF

# Create Application Insights agent config file
cat > $PROJECT_DIR/applicationinsights.json << EOF
{
  "connectionString": "InstrumentationKey=$INSTRUMENTATION_KEY",
  "role": {
    "name": "TraceTestApp",
    "instance": "test-instance"
  },
  "sampling": {
    "percentage": 100
  },
  "instrumentation": {
    "logging": {
      "level": "TRACE"
    },
    "micrometer": {
      "enabled": true
    }
  },
  "preview": {
    "instrumentation": {
      "springScheduling": {
        "enabled": true
      },
      "jdbc": {
        "enabled": true
      }
    }
  }
}
EOF

# Create agent config file
cat > $PROJECT_DIR/agent-config.properties << EOF
# OpenTelemetry Configuration
exporter.type=azure
azure.connection.string=InstrumentationKey=$INSTRUMENTATION_KEY

# Instrumentation Configuration
package.prefixes=com.test
debug.enabled=true
EOF

# Step 3: Build the application
echo "==== Building Test Application ===="
cd $PROJECT_DIR
if ! command -v mvn &> /dev/null; then
    echo "Maven not found. Please install Maven before continuing."
    exit 1
fi

# Verify Java 17 is available
echo "==== Verifying Java 17 is available ===="
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
echo "Current Java version: $JAVA_VERSION"

if [[ ! "$JAVA_VERSION" =~ ^17 ]]; then
    echo "WARNING: Current Java version is not 17. Make sure Java 17 is used for compilation and execution."
    echo "You may need to set JAVA_HOME or use a Java version manager."
fi

mvn clean package

# Verify your agent exists
if [ ! -f "$AGENT_JAR_PATH" ]; then
    echo "ERROR: Your custom agent JAR not found at: $AGENT_JAR_PATH"
    exit 1
else
    echo "Confirmed: Using your custom agent at: $AGENT_JAR_PATH"
fi

# Verify App Insights agent exists
if [ ! -f "$AI_AGENT_JAR" ]; then
    echo "ERROR: Application Insights agent JAR not found at: $AI_AGENT_JAR"
    exit 1
else
    echo "Confirmed: Using Application Insights agent at: $AI_AGENT_JAR"
fi

# Step 4: Run the application with both agents
echo "==== Starting Application with Both Agents ===="

# Add Java 17-specific JVM options and both agents
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED \
     -javaagent:$AI_AGENT_JAR \
     -javaagent:$AGENT_JAR_PATH \
     -jar target/trace-test-1.0-SNAPSHOT.jar &

APP_PID=$!
echo "Application started with PID: $APP_PID"

# Wait for application to start
echo "Waiting for application to start..."
sleep 20

# Step 5: Test the trace propagation
echo "==== Testing Trace Propagation ===="
curl http://localhost:$SERVER_PORT/test

# Give time for telemetry to be sent to Azure
echo "Waiting for telemetry to reach Azure (30 seconds)..."
sleep 30

# Step 6: Kill the application
echo "==== Shutting down application ===="
kill $APP_PID
sleep 5  # Give it time to shutdown gracefully

echo "==== Test Complete ===="
echo "NOTE: Check the logs for HTTP headers to see if trace context is being propagated"
echo "Look for headers like 'traceparent', 'Request-Id', etc."