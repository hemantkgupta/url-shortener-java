#!/bin/bash

set -e

# 1. Port-forward Envoy in background
echo "Port-forwarding Envoy..."
kubectl port-forward svc/envoy 10000:80 > /dev/null 2>&1 &
PF_PID=$!

# Wait for port-forward to be ready
echo "Waiting for port-forward to be ready..."
for i in {1..10}; do
    if curl -s http://localhost:10000/health > /dev/null; then
        echo "Port-forward ready!"
        break
    fi
    sleep 1
done

# Cleanup on exit
trap "kill $PF_PID 2>/dev/null || true" EXIT

UNIQUE_URL="https://example.com/url-$(date +%s)-$RANDOM"
echo "Testing URL shortening via Envoy with URL: $UNIQUE_URL"
RESPONSE=$(curl -s -X POST http://localhost:10000/api/v1/shorten \
    -H "Content-Type: application/json" \
    -d "{\"long_url\": \"$UNIQUE_URL\"}")

echo "Response: $RESPONSE"

if echo "$RESPONSE" | grep -q "short_url"; then
    echo "SUCCESS: Received short URL"
else
    echo "FAILURE: Did not receive short URL"
    exit 1
fi

# 3. Test Rate Limiting (Write API)
echo "Testing Rate Limiting for Write API (sending 15 requests)..."
for i in {1..15}; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:10000/api/v1/shorten \
        -H "Content-Type: application/json" \
        -d '{"long_url": "https://example.com/rate/limit/test"}')
    echo "Request $i: Status $STATUS"
done

# 4. Port-forward Envoy Read in background
echo "Port-forwarding Envoy Read..."
kubectl port-forward svc/envoy-read 10001:80 > /dev/null 2>&1 &
PF_READ_PID=$!

# Wait for port-forward to be ready
echo "Waiting for port-forward read to be ready..."
for i in {1..10}; do
    if curl -s http://localhost:10001/health > /dev/null; then
        echo "Port-forward read ready!"
        break
    fi
    sleep 1
done

# Cleanup on exit
trap "kill $PF_PID $PF_READ_PID 2>/dev/null || true" EXIT

# 5. Test Redirect via Envoy Read
echo "Testing URL redirect via Envoy Read..."
# Use the short code from the first request (extract from the end of the URL)
SHORT_CODE=$(echo "$RESPONSE" | grep -oP '(?<="short_url":")[^"]+' | sed 's/.*\///')
echo "Short Code: $SHORT_CODE"

REDIRECT_RESPONSE=$(curl -s -I http://localhost:10001/$SHORT_CODE)
echo "$REDIRECT_RESPONSE" | grep -q "HTTP/1.1 301" && echo "SUCCESS: Received 301 Redirect" || echo "FAILURE: Did not receive 301 Redirect"

# 6. Test Rate Limiting (Read API)
echo "Testing Rate Limiting for Read API (sending 15 requests)..."
for i in {1..15}; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:10001/$SHORT_CODE)
    echo "Request $i: Status $STATUS"
done

# 7. Verify storage
echo "Verifying storage..."

echo "Checking Redis for cache warming..."
# The short code should be in Redis
REDIS_VAL=$(kubectl exec deployment/redis -- redis-cli GET $SHORT_CODE)
if [ "$REDIS_VAL" != "" ]; then
    echo "SUCCESS: Found short URL in Redis: $REDIS_VAL"
else
    echo "FAILURE: Short URL not found in Redis"
fi

echo "Checking Kafka for events..."
# Get current offset to only read new messages
OFFSET=$(kubectl exec deployment/kafka -- /opt/kafka/bin/kafka-run-class.sh org.apache.kafka.tools.GetOffsetShell --bootstrap-server localhost:9092 --topic url-created --time -1 2>/dev/null | grep "url-created:0:" | cut -d: -f3)
if [ -z "$OFFSET" ]; then OFFSET=0; fi

# Perform one more request to ensure we catch an event after the offset
echo "Sending one more request to verify Kafka event..."
CURL_OUT=$(curl -s -X POST http://localhost:10000/api/v1/shorten -H "Content-Type: application/json" -d "{\"long_url\": \"https://example.com/kafka/test/$(date +%s)\"}")
# Use the short code from the first request (extract from the end of the URL)
NEW_SHORT_CODE=$(echo "$CURL_OUT" | grep -oP '(?<="short_url":")[^"]+' | sed 's/.*\///')

# Read messages from OFFSET
kubectl exec deployment/kafka -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic url-created --partition 0 --offset $OFFSET --max-messages 20 --timeout-ms 10000 > kafka_output.txt 2>/dev/null || true

if grep -q "$NEW_SHORT_CODE" kafka_output.txt; then
    echo "SUCCESS: Found short code in Kafka: $NEW_SHORT_CODE"
else
    echo "FAILURE: Short code $NEW_SHORT_CODE not found in Kafka"
    echo "Messages read from offset $OFFSET:"
    cat kafka_output.txt
fi
rm -f kafka_output.txt

echo "Verifying SigNoz Telemetry..."
# 8. Verify SigNoz Telemetry
echo "Checking ClickHouse for traces..."
# Wait a bit for telemetry to be exported and ingested
sleep 10
TRACE_COUNT=$(kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "SELECT count() FROM signoz_traces.otel_traces" 2>/dev/null || echo "0")
if [ "$TRACE_COUNT" -gt 0 ]; then
    echo "SUCCESS: Found $TRACE_COUNT traces in ClickHouse"
else
    echo "WARNING: No traces found in ClickHouse yet"
fi

echo "Checking ClickHouse for metrics..."
METRIC_COUNT=$(kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "SELECT count() FROM signoz_metrics.otel_metrics_sum" 2>/dev/null || echo "0")
if [ "$METRIC_COUNT" -gt 0 ]; then
    echo "SUCCESS: Found $METRIC_COUNT metrics in ClickHouse"
else
    echo "WARNING: No metrics found in ClickHouse yet"
fi

echo "Checking ClickHouse for logs..."
LOG_COUNT=$(kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "SELECT count() FROM signoz_logs.otel_logs" 2>/dev/null || echo "0")
if [ "$LOG_COUNT" -gt 0 ]; then
    echo "SUCCESS: Found $LOG_COUNT logs in ClickHouse"
else
    echo "WARNING: No logs found in ClickHouse yet"
fi

echo "Verifying Flink Job Status..."
FLINK_JOBS=$(kubectl exec deployment/flink-jobmanager -- curl -s http://localhost:8081/jobs)
if echo "$FLINK_JOBS" | grep -q "RUNNING"; then
    echo "SUCCESS: Flink job is RUNNING"
else
    echo "FAILURE: Flink job is not running"
    echo "Flink Jobs: $FLINK_JOBS"
    exit 1
fi

# 9. Run Playwright Integration Tests
echo "Waiting for analytics aggregation..."
sleep 15
echo "Running Playwright Integration Tests..."
cd frontend
corepack npm test
cd ..

echo "Verification complete!"
