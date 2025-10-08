#!/bin/bash
set -e

echo "=== Kafka Connect SSE Local Testing ==="
echo

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Function to check if a service is ready
wait_for_service() {
    local url=$1
    local service_name=$2
    local max_attempts=60
    local attempt=0
    
    echo -n "Waiting for ${service_name} to be ready..."
    while ! curl -s -o /dev/null -w "%{http_code}" "${url}" | grep -q "200\|404"; do
        attempt=$((attempt + 1))
        if [ $attempt -ge $max_attempts ]; then
            echo -e " ${RED}FAILED${NC}"
            echo "Timeout waiting for ${service_name}"
            return 1
        fi
        echo -n "."
        sleep 2
    done
    echo -e " ${GREEN}READY${NC}"
}

# Step 1: Build the connector
echo -e "${BLUE}Step 1: Building connector...${NC}"
mvn clean package -DskipTests
echo -e "${GREEN}✓ Connector built${NC}"
echo

# Step 2: Start Docker Compose
echo -e "${BLUE}Step 2: Starting Docker Compose services...${NC}"
docker-compose up -d
echo -e "${GREEN}✓ Services started${NC}"
echo

# Step 3: Wait for services
echo -e "${BLUE}Step 3: Waiting for services to be ready...${NC}"
wait_for_service "http://localhost:9092" "Kafka Broker"
wait_for_service "http://localhost:8081" "Schema Registry"
wait_for_service "http://localhost:8083" "Kafka Connect"
echo

# Step 4: Check installed connectors
echo -e "${BLUE}Step 4: Checking available connector plugins...${NC}"
curl -s http://localhost:8083/connector-plugins | jq -r '.[] | select(.class | contains("ServerSentEvents")) | .class'
echo

# Step 5: Deploy the connector
echo -e "${BLUE}Step 5: Deploying Wikipedia SSE connector...${NC}"
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/wikipedia-connector.json | jq '.'
echo
echo -e "${GREEN}✓ Connector deployed${NC}"
echo

# Step 6: Check connector status
echo -e "${BLUE}Step 6: Checking connector status...${NC}"
sleep 5
curl -s http://localhost:8083/connectors/wikipedia-sse-connector/status | jq '.'
echo

# Step 7: Instructions for viewing data
echo -e "${BLUE}=== Next Steps ===${NC}"
echo
echo "View connector logs:"
echo "  docker logs -f connect"
echo
echo "Check connector status:"
echo "  curl http://localhost:8083/connectors/wikipedia-sse-connector/status | jq '.'"
echo
echo "Consume messages from the topic:"
echo "  docker exec -it broker kafka-console-consumer --bootstrap-server localhost:9092 --topic wikipedia-changes --from-beginning"
echo
echo "View in Confluent Control Center:"
echo "  http://localhost:9021"
echo
echo "Stop everything:"
echo "  docker-compose down"
echo

