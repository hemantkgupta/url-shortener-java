#!/bin/bash

set -e

CLUSTER_NAME="url-shortener"

# 1. Create kind cluster if not exists
if ! kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
    echo "Creating kind cluster..."
    kind create cluster --name ${CLUSTER_NAME}
else
    echo "Kind cluster ${CLUSTER_NAME} already exists."
fi

# 2. Build Docker images
echo "Building Docker images..."
docker build -t write-api:latest -f backend/write-service/cmd/write-api/Dockerfile .
docker build -t cdc-worker:latest -f backend/write-service/cmd/cdc-worker/Dockerfile .
docker build -t read-api:latest -f backend/read-service/cmd/read-api/Dockerfile .
docker build -t analytics-api:latest -f backend/analytics-service/cmd/analytics-api/Dockerfile .
docker build -t flink-custom:latest -f backend/analytics-service/flink/Dockerfile backend/analytics-service/flink/
docker build -t frontend:latest --build-arg VITE_SHORT_LINK_BASE_URL=http://localhost:10001 -f frontend/Dockerfile .

# 3. Load images into kind
echo "Loading images into kind..."
kind load docker-image write-api:latest --name ${CLUSTER_NAME}
kind load docker-image cdc-worker:latest --name ${CLUSTER_NAME}
kind load docker-image read-api:latest --name ${CLUSTER_NAME}
kind load docker-image analytics-api:latest --name ${CLUSTER_NAME}
kind load docker-image flink-custom:latest --name ${CLUSTER_NAME}
kind load docker-image frontend:latest --name ${CLUSTER_NAME}

# 4. Deploy Infrastructure
echo "Deploying infrastructure..."
kubectl apply -f k8s/infra/

# 5. Wait for Infrastructure
echo "Waiting for infrastructure to be ready..."
kubectl wait --for=condition=available --timeout=300s deployment/spanner-emulator
kubectl wait --for=condition=available --timeout=300s deployment/etcd
kubectl wait --for=condition=available --timeout=300s deployment/redis
kubectl wait --for=condition=available --timeout=300s deployment/kafka
kubectl wait --for=condition=available --timeout=300s deployment/clickhouse
 
# 5.1 Deploy SigNoz
echo "Deploying SigNoz..."
kubectl apply -f k8s/signoz/
echo "Waiting for SigNoz to be ready..."
kubectl wait --for=condition=available --timeout=300s deployment/signoz-clickhouse
kubectl wait --for=condition=available --timeout=300s deployment/signoz-query-service
kubectl wait --for=condition=available --timeout=300s deployment/signoz-frontend
kubectl wait --for=condition=available --timeout=300s deployment/signoz-otel-collector

# 5.2 Initialize SigNoz ClickHouse Schema
echo "Initializing SigNoz ClickHouse Schema..."
kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "CREATE DATABASE IF NOT EXISTS signoz_traces"
kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "CREATE DATABASE IF NOT EXISTS signoz_metrics"
kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "CREATE DATABASE IF NOT EXISTS signoz_logs"
kubectl exec deployment/signoz-clickhouse -- clickhouse-client --query "CREATE TABLE IF NOT EXISTS signoz_metrics.distributed_updated_metadata (metric_name String, description String, unit String, type String, is_monotonic Int8, temporality String, last_updated_at DateTime) ENGINE = ReplacingMergeTree(last_updated_at) ORDER BY metric_name;"

# 5.5 Initialize Kafka Topics
echo "Initializing Kafka Topics..."
kubectl exec deployment/kafka -- /opt/kafka/bin/kafka-topics.sh --delete --bootstrap-server localhost:9092 --topic url-created --if-exists
kubectl exec deployment/kafka -- /opt/kafka/bin/kafka-topics.sh --delete --bootstrap-server localhost:9092 --topic click-events --if-exists
kubectl exec deployment/kafka -- /opt/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --replication-factor 1 --partitions 1 --topic url-created --if-not-exists
kubectl exec deployment/kafka -- /opt/kafka/bin/kafka-topics.sh --create --bootstrap-server localhost:9092 --replication-factor 1 --partitions 1 --topic click-events --if-not-exists

# 6. Initialize Spanner Emulator
echo "Initializing Spanner Emulator..."
kubectl rollout restart deployment/spanner-emulator
kubectl wait --for=condition=available --timeout=300s deployment/spanner-emulator
# Wait a bit for the emulator to be actually ready to accept connections
sleep 10
kubectl delete pod spanner-init --ignore-not-found
kubectl run spanner-init --image=curlimages/curl --restart=Never -- /bin/sh -c \
  "curl -X POST http://spanner-emulator:9020/v1/projects/url-shortener/instances -d '{\"instanceId\": \"main\", \"instance\": {\"config\": \"projects/url-shortener/instanceConfigs/emulator-config\", \"displayName\": \"Main Instance\", \"nodeCount\": 1}}' && \
   curl -X POST http://spanner-emulator:9020/v1/projects/url-shortener/instances/main/databases -d '{\"createStatement\": \"CREATE DATABASE urls\"}' && \
   curl -X PATCH http://spanner-emulator:9020/v1/projects/url-shortener/instances/main/databases/urls/ddl -d '{\"statements\": [\"CREATE TABLE url_mappings (id STRING(MAX) NOT NULL, long_url STRING(MAX) NOT NULL, user_id STRING(MAX), created_at TIMESTAMP NOT NULL OPTIONS (allow_commit_timestamp=true)) PRIMARY KEY (id)\"]}'"

# 7. Initialize ClickHouse Schema
echo "Initializing ClickHouse Schema..."
# Wait a bit for ClickHouse to be fully ready to accept connections
sleep 10
kubectl exec deployment/clickhouse -- clickhouse-client --query "$(cat backend/analytics-service/clickhouse.sql)"

# 8. Deploy Application
echo "Deploying application..."
kubectl apply -R -f k8s/envoy/
kubectl apply -R -f k8s/write-api/
kubectl apply -R -f k8s/cdc-worker/
kubectl apply -R -f k8s/read-api/
kubectl apply -R -f k8s/analytics-api/
kubectl apply -f k8s/flink/
kubectl apply -f k8s/frontend.yaml

# 11. Restart deployments to pick up new images/configs
echo "Restarting deployments to pick up new images..."
kubectl rollout restart deployment/write-api
kubectl rollout restart deployment/cdc-worker
kubectl rollout restart deployment/read-api
kubectl rollout restart deployment/analytics-api
kubectl rollout restart deployment/flink-jobmanager
kubectl rollout restart deployment/flink-taskmanager
kubectl rollout restart deployment/envoy
kubectl rollout restart deployment/frontend

# 12. Wait for application to be ready
echo "Waiting for application to be ready..."
kubectl wait --for=condition=available --timeout=300s deployment/envoy
kubectl wait --for=condition=available --timeout=300s deployment/envoy-read
kubectl wait --for=condition=available --timeout=300s deployment/write-api
kubectl wait --for=condition=available --timeout=300s deployment/cdc-worker
kubectl wait --for=condition=available --timeout=300s deployment/read-api
kubectl wait --for=condition=available --timeout=300s deployment/analytics-api
kubectl wait --for=condition=available --timeout=300s deployment/flink-jobmanager
kubectl wait --for=condition=available --timeout=300s deployment/flink-taskmanager
kubectl wait --for=condition=available --timeout=300s deployment/frontend

# 12.1 Submit Flink SQL Job
echo "Submitting Flink SQL Job..."
# Wait for JobManager to be fully ready to accept jobs
echo "Waiting for Flink JobManager to be ready to accept jobs..."
for i in {1..30}; do
    if kubectl exec deployment/flink-jobmanager -- curl -s http://localhost:8081/overview > /dev/null 2>&1; then
        echo "Flink JobManager is ready!"
        break
    fi
    echo "Waiting for Flink JobManager... ($i/30)"
    sleep 2
done

kubectl exec deployment/flink-jobmanager -- ./bin/flink run -d -c org.apache.flink.table.client.SqlClient /opt/flink/lib/flink-sql-client-*.jar -f /opt/flink/click_events_to_clickhouse.sql || \
kubectl exec deployment/flink-jobmanager -- ./bin/sql-client.sh -f /opt/flink/click_events_to_clickhouse.sql

echo "Setup complete!"

# 13. Port-forward Envoy in background
echo "Exposing services via port-forward..."
# Kill existing port-forwards if any
pkill -f "port-forward svc/envoy" || true
pkill -f "port-forward svc/envoy-read" || true

kubectl port-forward svc/envoy 10000:80 > /dev/null 2>&1 &
kubectl port-forward svc/envoy-read 10001:80 > /dev/null 2>&1 &

echo "--------------------------------------------------"
echo "HyperShort is now accessible at:"
echo "Frontend & Write API: http://localhost:10000"
echo "Read API (Redirects): http://localhost:10001"
echo "--------------------------------------------------"
echo "To test, run: ./test.sh"
