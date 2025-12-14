#!/bin/bash
# =============================================================================
# Build and Push Generator Image
# =============================================================================
# Builds the configuration generator Docker image with PyYAML pre-installed
#
# Usage:
#   ./scripts/build-generator-image.sh          # Build locally
#   ./scripts/build-generator-image.sh --push   # Build and push to Docker Hub
# =============================================================================

set -e

IMAGE_NAME="ghcr.io/couchbaselabs/couchbase-fhir-ce/fhir-generator"
TAG="latest"

echo "🔨 Building generator image: $IMAGE_NAME:$TAG"

# Build the image
docker build -f Dockerfile.generator -t "$IMAGE_NAME:$TAG" .

echo "✅ Image built successfully!"
echo ""
docker images "$IMAGE_NAME"

# Push if requested
if [ "$1" = "--push" ]; then
    echo ""
    echo "📤 Pushing to Docker Hub..."
    docker push "$IMAGE_NAME:$TAG"
    echo "✅ Pushed: $IMAGE_NAME:$TAG"
fi

echo ""
echo "💡 To use this image:"
echo "   docker run --rm -v \$(pwd):/work $IMAGE_NAME:$TAG python scripts/generate.py config.yaml"

