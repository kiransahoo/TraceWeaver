#!/bin/bash

echo "==== Starting Application Insights Agent + Custom Agent with Debug Logging ===="

# Set the paths
AI_AGENT_JAR="/Users/kiransahoo/Desktop/code/otel-instrumentation/otel-agent-extension/otel-agent-with-custom/my-standalone-agent/trace-test/applicationinsights-agent-3.7.2.jar"
CUSTOM_AGENT_JAR_PATH="/Users/kiransahoo/.m2/repository/com/myorg/trace-standalone-agent/1.0.0/trace-standalone-agent-1.0.0.jar"
APP_JAR="/Users/kiransahoo/Desktop/code/otel-instrumentation/otel-agent-extension/otel-agent-with-custom/my-standalone-agent/trace-test/target/trace-test-1.0-SNAPSHOT.jar"
AI_CONFIG="/Users/kiransahoo/Desktop/code/otel-instrumentation/otel-agent-extension/otel-agent-with-custom/my-standalone-agent/trace-test/applicationinsights.json"
CUSTOM_CONFIG="./trace-test/agent-config.properties"
LOG_FILE="agent-debug.log"

# Verify all required files exist
echo "Verifying required files..."
if [ ! -f "$AI_AGENT_JAR" ]; then
    echo "ERROR: Application Insights agent JAR not found at: $AI_AGENT_JAR"
    exit 1
fi

if [ ! -f "$CUSTOM_AGENT_JAR_PATH" ]; then
    echo "ERROR: Custom agent JAR not found at: $CUSTOM_AGENT_JAR_PATH"
    exit 1
fi

if [ ! -f "$APP_JAR" ]; then
    echo "ERROR: Application JAR not found at: $APP_JAR"
    echo "Make sure you've built the project with 'mvn clean package'"
    exit 1
fi

if [ ! -f "$AI_CONFIG" ]; then
    echo "ERROR: Application Insights config not found at: $AI_CONFIG"
    exit 1
fi

if [ ! -f "$CUSTOM_CONFIG" ]; then
    echo "ERROR: Custom agent config not found at: $CUSTOM_CONFIG"
    exit 1
fi

echo "All files verified. Starting application with debug logging..."
echo "Debug output will be saved to: $LOG_FILE"

# Run with comprehensive Java 17 compatibility flags and debug logging
java \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.base/java.text=ALL-UNNAMED \
    --add-opens=java.base/java.time=ALL-UNNAMED \
    --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
    --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/sun.net.util=ALL-UNNAMED \
    --add-opens=java.management/sun.management=ALL-UNNAMED \
    --add-opens=java.logging/java.util.logging=ALL-UNNAMED \
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
    -Djdk.attach.allowAttachSelf=true \
    -Dhttp.nonProxyHosts="localhost|127.0.0.1" \
    -Dhttps.nonProxyHosts="localhost|127.0.0.1" \
    -Dhttp.proxyHost="" \
    -Dhttps.proxyHost="" \
    -javaagent:$CUSTOM_AGENT_JAR_PATH \
    -javaagent:$AI_AGENT_JAR \
    -Dapplicationinsights.configuration.file=$AI_CONFIG \
    -Dcom.tracer.genericagent.config.file=$CUSTOM_CONFIG \
    -jar $APP_JAR > $LOG_FILE 2>&1 &

APP_PID=$!
echo "Application started with PID: $APP_PID"

# Wait for startup and test
echo "Waiting for application to start..."
sleep 20

echo "Testing multiple endpoints to verify trace propagation..."

echo ""
echo "1. Testing original endpoint: /test"
curl -s http://localhost:6070/test
sleep 2

echo ""
echo "2. Testing simple service layer: /users"
curl -s http://localhost:6070/users
sleep 2

echo ""
echo "3. Testing service -> repository -> notification: /users/123"
curl -s http://localhost:6070/users/123
sleep 2

echo ""
echo "4. Testing complex multi-layer with HTTP call: /users/123/profile"
curl -s http://localhost:6070/users/123/profile
sleep 3

echo ""
echo "Waiting for all traces to be processed..."
sleep 10

# Analyze the debug logs
echo ""
echo "===== DEBUG ANALYSIS ====="

echo ""
echo "1. Checking for custom agent initialization:"
grep -i "GenericByteBuddyAgent\|EnhancedAdvisor\|Successfully installed instrumentation" $LOG_FILE | head -5

echo ""
echo "2. Checking for OpenTelemetry setup:"
grep -i "OpenTelemetry\|TracerProvider\|already registered\|already configured" $LOG_FILE | head -5

echo ""
echo "3. Checking for method instrumentation (most important):"
grep -i "GenericMethodAdvice\|ENTER\|EXIT\|firstLevelMethod\|secondLevelMethod\|UserService\|UserRepository\|ExternalApiService\|NotificationService" $LOG_FILE | head -20

echo ""
echo "4. Checking for Application Insights agent activity:"
grep -i "ApplicationInsights\|Microsoft.ApplicationInsights" $LOG_FILE | head -3

echo ""
echo "5. Checking for span creation:"
grep -i "span\|trace" $LOG_FILE | grep -v "stack\|class" | head -5

echo ""
echo "6. Checking for any errors:"
grep -i "error\|exception\|failed" $LOG_FILE | head -5

echo ""
echo "===== SUMMARY ====="
METHOD_TRACES=$(grep -c "ENTER\|EXIT" $LOG_FILE)
SERVICE_TRACES=$(grep -c "UserService\|UserRepository\|ExternalApiService\|NotificationService" $LOG_FILE)
echo "Custom agent method traces found: $METHOD_TRACES"
echo "Service layer traces found: $SERVICE_TRACES"

if [ $METHOD_TRACES -gt 0 ]; then
    echo "✅ Custom agent IS instrumenting methods"
    if [ $SERVICE_TRACES -gt 0 ]; then
        echo "✅ Service layer instrumentation working"
        echo "   All traces should appear in Application Insights with correlated operation IDs"
    else
        echo "⚠️  Service layer instrumentation may not be working"
        echo "   Check if service classes are being instrumented"
    fi
else
    echo "❌ Custom agent is NOT instrumenting methods"
    echo "   Check the initialization messages above"
fi

echo ""
echo "===== TRACE CORRELATION CHECK ====="
echo "Checking trace ID correlation across different endpoints:"

echo ""
echo "Trace IDs from /test endpoint:"
grep -A5 -B5 "/test endpoint called" $LOG_FILE | grep -o "traceId=[a-f0-9]*" | head -3

echo ""
echo "Trace IDs from /users endpoint:"
grep -A5 -B5 "/users endpoint called" $LOG_FILE | grep -o "traceId=[a-f0-9]*" | head -3

echo ""
echo "Trace IDs from /users/123/profile endpoint:"
grep -A10 -B5 "/users/123/profile endpoint called" $LOG_FILE | grep -o "traceId=[a-f0-9]*" | head -5

echo ""
echo "===== DETAILED SERVICE LAYER ANALYSIS ====="
echo "UserService method calls:"
grep -c "UserService\." $LOG_FILE || echo "0"

echo "UserRepository method calls:"
grep -c "UserRepository\." $LOG_FILE || echo "0"

echo "ExternalApiService method calls:"
grep -c "ExternalApiService\." $LOG_FILE || echo "0"

echo "NotificationService method calls:"
grep -c "NotificationService\." $LOG_FILE || echo "0"

echo ""
echo "Application is running. To stop: kill $APP_PID"
echo "Full debug log available in: $LOG_FILE"
echo ""
echo "To test individual endpoints:"
echo "  curl http://localhost:6070/test              # Original test"
echo "  curl http://localhost:6070/users             # Service layer test"
echo "  curl http://localhost:6070/users/123         # Multi-service test"
echo "  curl http://localhost:6070/users/123/profile # Complex HTTP chain test"
echo ""
echo "To watch live logs: tail -f $LOG_FILE"
echo "To filter for specific traces: grep 'traceId=' $LOG_FILE"
echo "To see service layer activity: grep -i 'UserService\\|UserRepository\\|ExternalApiService\\|NotificationService' $LOG_FILE"