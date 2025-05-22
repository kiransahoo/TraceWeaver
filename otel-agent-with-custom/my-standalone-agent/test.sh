#!/bin/bash
set -e

echo "==== Starting Application Insights 3.1.0 + Agent Integration Test (Java 17) ===="

# Define variables
RESOURCE_GROUP="TraceTestGroup"
LOCATION="eastus"
APP_INSIGHTS_NAME="TraceTest$(date +%s)"  # Unique name
PROJECT_DIR="trace-test"
# Your custom agent path
AGENT_JAR_PATH="/Users/kiransahoo/.m2/repository/com/myorg/trace-standalone-agent/1.0.0/trace-standalone-agent-1.0.0.jar"
SERVER_PORT=6070  # Using port 6070 as requested

# Step 1: Azure setup
echo "==== Creating Azure Resources ===="
# Check if Azure CLI is installed
if ! command -v az &> /dev/null; then
    echo "Azure CLI not found. Installing..."
    curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash
fi

# Login to Azure (comment out if already logged in)
#az login

echo "Registering required Azure resource providers..."
#az provider register --namespace Microsoft.OperationalInsights
#az provider register --namespace Microsoft.Insights

echo "Waiting for resource providers to register (this may take a few minutes)..."
#az provider show -n Microsoft.OperationalInsights --query "registrationState" -o tsv
#az provider show -n Microsoft.Insights --query "registrationState" -o tsv

# Create resource group
echo "Creating resource group..."
#az group create --name $RESOURCE_GROUP --location $LOCATION

# Create App Insights resource
echo "Creating Application Insights resource..."
#az monitor app-insights component create --app $APP_INSIGHTS_NAME --resource-group $RESOURCE_GROUP --location $LOCATION --kind web

# Get the instrumentation key
INSTRUMENTATION_KEY=fcec979a-1607-4847-a8a1-73645d5ae20e
echo "Instrumentation Key: $INSTRUMENTATION_KEY"

# Step 2: Create test application
echo "==== Creating Test Application ===="
mkdir -p $PROJECT_DIR/src/main/java/com/test
mkdir -p $PROJECT_DIR/src/main/resources

# Create pom.xml
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
        <applicationinsights.version>3.1.0</applicationinsights.version>
    </properties>

    <dependencies>
        <!-- Spring Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Application Insights 3.1.0 -->

        <dependency>
            <groupId>com.microsoft.azure</groupId>
            <artifactId>applicationinsights-agent</artifactId>
            <version>${applicationinsights.version}</version>
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

# Create Application.java
cat > $PROJECT_DIR/src/main/java/com/test/Application.java << EOF
package com.test;

import com.microsoft.applicationinsights.TelemetryClient;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        // Print Java version for verification
        System.out.println("Java version: " + System.getProperty("java.version"));

        // Log Application Insights version
        String aiVersion = System.getProperty("applicationinsights.version", "not set");
        System.out.println("Using Application Insights version: " + aiVersion);
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

        @Autowired
        private TelemetryClient telemetryClient;

        @GetMapping("/test")
        public String test() {
            System.out.println("\n=== /test endpoint called ===");

            // Start with App Insights operation
            telemetryClient.trackEvent("TestStarted");
            System.out.println("Event tracked: TestStarted");

            // First level method - should use parent context
            firstLevelMethod();

            try {
                // Give time for first level to be processed
                TimeUnit.SECONDS.sleep(1);

                // Make HTTP call to another endpoint
                // This should propagate trace context via HTTP headers
                System.out.println("\n=== Making HTTP call to /downstream ===");
                ResponseEntity<String> response = restTemplate.getForEntity(
                    "http://localhost:${SERVER_PORT}/downstream", String.class);

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
                telemetryClient.trackTrace("First level method executed");
                System.out.println("First level method completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void secondLevelMethod() {
            System.out.println("=== secondLevelMethod called ===");

            // Traced method - should be captured by your agent
            // Should maintain correlation with the parent operation
            try {
                TimeUnit.MILLISECONDS.sleep(500);
                telemetryClient.trackTrace("Second level method executed");
                System.out.println("Second level method completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
EOF

# Replace SERVER_PORT placeholder with actual port
sed -i "" "s/\${SERVER_PORT}/$SERVER_PORT/g" $PROJECT_DIR/src/main/java/com/test/Application.java

# Create application.properties with the instrumentation key
cat > $PROJECT_DIR/src/main/resources/application.properties << EOF
# Application Insights Configuration
azure.application-insights.instrumentation-key=$INSTRUMENTATION_KEY
azure.application-insights.web.enabled=true
azure.application-insights.default-modules.ProcessPerformanceCountersModule.enabled=true

# Enable debug logging for App Insights
logging.level.com.microsoft.applicationinsights=DEBUG

# Enable HTTP client tracing for App Insights
azure.application-insights.web.client-request-telemetry-enabled=true

# Custom port
server.port=$SERVER_PORT
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

# Verify Application Insights 3.1.0 is being used
echo "==== Verifying Application Insights 3.1.0 JAR ===="
AI_JAR=$(find ~/.m2 -name "applicationinsights-web-3.1.0.jar" | head -1)

if [ -z "$AI_JAR" ]; then
    echo "WARNING: Application Insights 3.1.0 JAR not found in Maven cache"
    echo "Checking what versions are available..."
    find ~/.m2 -name "applicationinsights-web-*.jar"
    echo "Continuing anyway as Maven should have downloaded it during build..."
else
    echo "Confirmed: Using Application Insights 3.1.0: $AI_JAR"
fi

# Extract the AI version from the POM to ensure consistency
AI_VERSION=$(grep -o '<applicationinsights.version>[^<]*</applicationinsights.version>' pom.xml | sed 's/<applicationinsights.version>\(.*\)<\/applicationinsights.version>/\1/')
echo "Using Application Insights version from POM: $AI_VERSION"

# Verify your agent exists
if [ ! -f "$AGENT_JAR_PATH" ]; then
    echo "ERROR: Your agent JAR not found at: $AGENT_JAR_PATH"
    exit 1
else
    echo "Confirmed: Using your agent at: $AGENT_JAR_PATH"
fi

# Step 4: Run the application with both App Insights and your agent
echo "==== Starting Application with Agent ===="

# Add Java 17-specific JVM options
java --add-opens=java.base/java.lang=ALL-UNNAMED \
     --add-opens=java.base/java.util=ALL-UNNAMED \
     -javaagent:$AGENT_JAR_PATH \
     -Dcom.tracer.genericagent.config.file=agent-config.properties \
     -Dapplicationinsights.version=$AI_VERSION \
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
echo "Waiting for telemetry to reach Azure (60 seconds)..."
sleep 60

# Step 6: Kill the application
echo "==== Shutting down application ===="
kill $APP_PID
sleep 5  # Give it time to shutdown gracefully

# Step 7: Fetch and display trace information from Azure
echo "==== Retrieving Traces from Azure ===="
echo "To view the traces in the Azure portal:"
echo "1. Go to: https://portal.azure.com/"
echo "2. Navigate to Application Insights resource: $APP_INSIGHTS_NAME"
echo "3. Go to 'Transaction Search'"
echo "4. Look for the 'test' operation"
echo "5. Verify that all spans have the same operation ID"

echo "==== Test Complete ===="
echo "NOTE: You can view logs in your generic-agent.log file to verify context propagation"
echo "Look for lines containing 'Found valid parent' or similar debug messages"