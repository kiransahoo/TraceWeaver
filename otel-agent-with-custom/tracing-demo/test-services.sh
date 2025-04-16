#!/bin/bash

echo "Sending test request to Service 1..."
curl http://localhost:8081/api/process/test-data

echo -e "\n\nCheck Jaeger UI for trace:"
echo "http://localhost:16686"
