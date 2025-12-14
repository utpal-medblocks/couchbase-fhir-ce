#!/bin/bash
# =============================================================================
# Apply Configuration Changes
# =============================================================================
# Regenerates docker-compose.yml and haproxy.cfg from config.yaml,
# then restarts services.
#
# Usage: ./scripts/apply-config.sh [config.yaml]
#
# Requirements: Docker (no Python/pip needed!)
# =============================================================================

set -e

CONFIG_FILE=${1:-./config.yaml}
GENERATOR_IMAGE="ghcr.io/couchbaselabs/couchbase-fhir-ce/fhir-generator:latest"

echo "🔄 Applying configuration from: $CONFIG_FILE"
echo ""

# Validate config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ Error: Config file not found: $CONFIG_FILE"
    exit 1
fi

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "❌ Error: Docker not installed"
    echo "   Install: curl -fsSL https://get.docker.com | sh"
    exit 1
fi

# Check if generator image exists, build if not
if ! docker image inspect "$GENERATOR_IMAGE" >/dev/null 2>&1; then
    echo "📦 Building generator image (one-time setup)..."
    docker build -f Dockerfile.generator -t "$GENERATOR_IMAGE" .
fi

# Generate configuration files using containerized generator
echo "🔧 Generating configuration files..."
docker run --rm \
    -v "$(pwd):/work" \
    -u "$(id -u):$(id -g)" \
    "$GENERATOR_IMAGE" \
    python scripts/generate.py "$CONFIG_FILE"

echo ""
echo "🐳 Applying changes to services..."

# Check if services are running
if docker-compose ps 2>/dev/null | grep -q "Up"; then
    # Services running - rebuild and restart them
    docker-compose down
    docker-compose up -d --build
    echo "✅ Services built and restarted with new configuration!"
else
    # Services not running - build and start them
    docker-compose up -d --build
    echo "✅ Services built and started with new configuration!"
fi

echo ""
echo "📊 Service status:"
docker-compose ps

echo ""
echo "💡 Tips:"
echo "   • View logs: docker-compose logs -f"
echo "   • Stop services: docker-compose down"
echo "   • Update config: edit config.yaml, then run this script again"
