#!/bin/bash

# Couchbase FHIR CE Install Script
# Usage: curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml

set -e

CONFIG_FILE="$1"

echo "🚀 Installing/Upgrading Couchbase FHIR CE..."

# Check if config file provided
if [ -z "$CONFIG_FILE" ]; then
    echo "❌ Error: Please provide a config file"
    echo "Usage: curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml"
    exit 1
fi

# Check if config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ Error: Config file '$CONFIG_FILE' not found"
    exit 1
fi

# Check if docker and docker-compose are installed
if ! command -v docker &> /dev/null; then
    echo "❌ Error: Docker is not installed. Please install Docker first."
    exit 1
fi

# Detect which docker-compose version is available
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE="docker-compose"
elif docker compose version &> /dev/null 2>&1; then
    DOCKER_COMPOSE="docker compose"
else
    echo "❌ Error: Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

echo "🔧 Using: $DOCKER_COMPOSE"

# Create installation directory
INSTALL_DIR="couchbase-fhir-ce"
mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"

echo "📁 Working in directory: $(pwd)"

# Download docker-compose.yml from GitHub
echo "📥 Downloading docker-compose.yml..."
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/docker-compose.user.yml -o docker-compose.yml

# Download haproxy.cfg from GitHub
echo "📥 Downloading haproxy.cfg..."
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/haproxy.cfg -o haproxy.cfg

# Copy user's config file
echo "📋 Using config file: $CONFIG_FILE"
cp "../$CONFIG_FILE" config.yaml

# Stop existing containers (if any)
echo "🛑 Stopping existing containers..."
$DOCKER_COMPOSE down 2>/dev/null || true

# Pull latest images from GitHub Container Registry
echo "📦 Pulling latest images..."
$DOCKER_COMPOSE pull

# Start containers
echo "🚀 Starting Couchbase FHIR CE..."
$DOCKER_COMPOSE up -d

# Wait a moment for containers to start
sleep 5

# Check if containers are running
if $DOCKER_COMPOSE ps | grep -q "Up"; then
    echo ""
    echo "✅ Couchbase FHIR CE is now running!"
    echo "🌐 Access the FHIR server at: http://localhost"
    echo ""
    echo "📋 Useful commands:"
    echo "   View logs:    $DOCKER_COMPOSE logs -f"
    echo "   Stop:         $DOCKER_COMPOSE down"
    echo "   Restart:      $DOCKER_COMPOSE restart"
    echo "   Status:       $DOCKER_COMPOSE ps"
else
    echo "❌ Error: Containers failed to start. Check logs with: $DOCKER_COMPOSE logs"
    exit 1
fi