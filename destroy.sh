#!/bin/bash

set -e

CLUSTER_NAME="url-shortener"

echo "Deleting Kubernetes resources..."
kubectl delete -R -f k8s/ --ignore-not-found

echo "Deleting kind cluster..."
if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
    kind delete cluster --name ${CLUSTER_NAME}
else
    echo "Kind cluster ${CLUSTER_NAME} does not exist."
fi

echo "Cleanup complete!"
