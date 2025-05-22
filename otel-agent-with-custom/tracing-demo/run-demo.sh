#!/bin/bash
# Path to custom agent and OTel agent
CUSTOM_AGENT_PATH="/Users/kiransahoo/Desktop/code/otel-instrumentation/otel-agent-extension/otel-agent-with-custom/my-standalone-agent/target/trace-standalone-agent-1.0.0.jar"
OTEL_AGENT_PATH="/Users/kiransahoo/Desktop/code/otel-instrumentation/otel-agent-extension/otel-agent-with-custom/otel-agent"
CONFIG_FILE="$(pwd)/agent-config/agent.properties"
echo "Using config file at: ${CONFIG_FILE}"
cat "${CONFIG_FILE}" 
echo "Stopping any existing service instances..."
pkill -f "service1-0.0.1-SNAPSHOT.jar" || true
pkill -f "service2-0.0.1-SNAPSHOT.jar" || true
sleep 2
# Check that Jaeger is running
if ! docker ps | grep -q jaeger; then
  echo "Jaeger container not running. Starting it..."
  docker run -d --name jaeger \
    -e COLLECTOR_ZIPKIN_HOST_PORT=:9411 \
    -e COLLECTOR_OTLP_ENABLED=true \
    -p 6831:6831/udp \
    -p 6832:6832/udp \
    -p 5778:5778 \
    -p 16686:16686 \
    -p 4317:4317 \
    -p 4318:4318 \
    -p 14250:14250 \
    -p 14268:14268 \
    -p 14269:14269 \
    -p 9411:9411 \
    jaegertracing/all-in-one:latest
fi
echo "Building Service 1..."
cd service1
mvn clean package
cd ..
echo "Building Service 2..."
cd service2
mvn clean package
cd ..
# Start Service 2 first (in the background)
echo "Starting Service 2 with OTel agent and custom agent..."
cd service2
java -javaagent:"${OTEL_AGENT_PATH}/opentelemetry-javaagent.jar" \
     -javaagent:"${CUSTOM_AGENT_PATH}" \
     -Dotel.traces.exporter=otlp \
     -Dotel.exporter.otlp.protocol=grpc \
     -Dotel.exporter.otlp.endpoint=http://localhost:4317/ \
     -Dotel.service.name=service2 \
     -Dagent.config.file="${CONFIG_FILE}" \
     -Dspring.main.banner-mode=off \
     -Dlogging.level.root=WARN \
     -Dorg.springframework.boot.logging.LoggingSystem=org.springframework.boot.logging.java.JavaLoggingSystem \
     -Dspring.main.log-startup-info=false \
     -jar target/service2-0.0.1-SNAPSHOT.jar &
SERVICE2_PID=$!
cd ..
# Wait for Service 2 to start
echo "Waiting for Service 2 to start..."
sleep 10
# Start Service 1
echo "Starting Service 1 with OTel agent and custom agent..."
cd service1
java -javaagent:"${OTEL_AGENT_PATH}/opentelemetry-javaagent.jar" \
     -javaagent:"${CUSTOM_AGENT_PATH}" \
     -Dotel.traces.exporter=otlp \
     -Dotel.exporter.otlp.protocol=grpc \
     -Dotel.exporter.otlp.endpoint=http://localhost:4317/ \
     -Dotel.service.name=service1 \
     -Dagent.config.file="${CONFIG_FILE}" \
     -Dspring.main.banner-mode=off \
     -Dlogging.level.root=WARN \
     -Dorg.springframework.boot.logging.LoggingSystem=org.springframework.boot.logging.java.JavaLoggingSystem \
     -Dspring.main.log-startup-info=false \
     -jar target/service1-0.0.1-SNAPSHOT.jar &
SERVICE1_PID=$!
cd ..
echo "Both services are running!"
echo "Service 1 PID: $SERVICE1_PID"
echo "Service 2 PID: $SERVICE2_PID"
echo ""
echo "You can test the services with:"
echo "curl http://localhost:8081/api/process/test-data"
echo ""
echo "Then view traces at: http://localhost:16686"
echo ""
echo "Press Ctrl+C to stop the services"
# Keep script running to show logs and allow for Ctrl+C to work
wait