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
    "$CONFIG_FILE"

echo ""
echo "🐳 Applying changes to services..."

# Detect docker compose command
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE="docker-compose"
elif docker compose version &> /dev/null 2>&1; then
    DOCKER_COMPOSE="docker compose"
else
    echo "❌ Error: Docker Compose not installed"
    exit 1
fi

# Check if source code exists (dev environment)
if [ -d "./backend" ] && [ -d "./frontend" ]; then
    # Development: Build from source
    BUILD_FLAG="--build"
    ACTION="built and"
else
    # Production: Pull pre-built images
    BUILD_FLAG=""
    ACTION=""
    echo "📦 Pulling pre-built images..."
    $DOCKER_COMPOSE pull 2>/dev/null || true
fi

# Check if services are running
if $DOCKER_COMPOSE ps 2>/dev/null | grep -q "Up"; then
    # Services running - restart them
    $DOCKER_COMPOSE down
    $DOCKER_COMPOSE up -d $BUILD_FLAG
    echo "✅ Services ${ACTION} restarted with new configuration!"
else
    # Services not running - start them
    $DOCKER_COMPOSE up -d $BUILD_FLAG
    echo "✅ Services ${ACTION} started with new configuration!"
fi

echo ""
echo "📊 Service status:"
$DOCKER_COMPOSE ps

echo ""
echo "💡 Tips:"
echo "   • View logs: $DOCKER_COMPOSE logs -f"
echo "   • Stop services: $DOCKER_COMPOSE down"
echo "   • Update config: edit config.yaml, then run this script again"
