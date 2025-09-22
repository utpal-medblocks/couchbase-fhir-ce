#!/bin/bash

# Couchbase FHIR CE Install Script
# 
# This script ONLY downloads:
# - docker-compose.yml (container definitions)
# - haproxy.cfg (load balancer config)  
# - checksums.txt (file integrity verification)
# - Pre-built images from ghcr.io/couchbaselabs/*
# No executable code is downloaded or run from external sources.
#
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

# Download files from GitHub
echo "📥 Downloading installation files..."
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/docker-compose.user.yml -o docker-compose.yml
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/haproxy.cfg -o haproxy.cfg
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/checksums.txt -o checksums.txt

# Verify file integrity
echo "🔐 Verifying file integrity..."
if command -v sha256sum &> /dev/null; then
    # Create temporary checksums for downloaded files only
    echo "357763e433c6b119fc1aa0fabf4cf754a7ccf46fd6c65e10cae1034dc2f21f37  docker-compose.yml" > temp_checksums.txt
    echo "707181e53db555589ea67814c57677d8f15dc3edefc06ceec8b7526fc4e2f405  haproxy.cfg" >> temp_checksums.txt
    
    if ! sha256sum -c temp_checksums.txt --quiet 2>/dev/null; then
        echo "❌ Warning: File integrity verification failed. Files may have been tampered with."
        echo "   Proceeding anyway, but please report this issue."
    else
        echo "✅ File integrity verified"
    fi
    rm -f temp_checksums.txt
elif command -v shasum &> /dev/null; then
    # macOS fallback
    echo "357763e433c6b119fc1aa0fabf4cf754a7ccf46fd6c65e10cae1034dc2f21f37  docker-compose.yml" > temp_checksums.txt
    echo "707181e53db555589ea67814c57677d8f15dc3edefc06ceec8b7526fc4e2f405  haproxy.cfg" >> temp_checksums.txt
    
    if ! shasum -a 256 -c temp_checksums.txt --quiet 2>/dev/null; then
        echo "❌ Warning: File integrity verification failed. Files may have been tampered with."
        echo "   Proceeding anyway, but please report this issue."
    else
        echo "✅ File integrity verified"
    fi
    rm -f temp_checksums.txt
else
    echo "⚠️  Warning: Cannot verify file integrity (sha256sum/shasum not available)"
fi

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
    
    # Auto-detect hostname for access URL
    ACCESS_URL=""
    
    # Try AWS EC2 metadata
    if command -v curl &> /dev/null; then
        AWS_HOSTNAME=$(curl -s --max-time 3 --connect-timeout 2 http://169.254.169.254/latest/meta-data/public-hostname 2>/dev/null || echo "")
        if [ -n "$AWS_HOSTNAME" ] && [ "$AWS_HOSTNAME" != "" ]; then
            ACCESS_URL="http://$AWS_HOSTNAME"
        fi
    fi
    
    # Try Google Cloud metadata
    if [ -z "$ACCESS_URL" ] && command -v curl &> /dev/null; then
        GCP_IP=$(curl -s --max-time 2 -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip 2>/dev/null || echo "")
        if [ -n "$GCP_IP" ]; then
            ACCESS_URL="http://$GCP_IP"
        fi
    fi
    
    # Try Azure metadata
    if [ -z "$ACCESS_URL" ] && command -v curl &> /dev/null; then
        AZURE_IP=$(curl -s --max-time 2 -H "Metadata:true" "http://169.254.169.254/metadata/instance/network/interface/0/ipv4/ipAddress/0/publicIpAddress?api-version=2021-02-01&format=text" 2>/dev/null || echo "")
        if [ -n "$AZURE_IP" ]; then
            ACCESS_URL="http://$AZURE_IP"
        fi
    fi
    
    # Fallback to localhost or show generic message
    if [ -n "$ACCESS_URL" ]; then
        echo "🌐 Access the FHIR server at: $ACCESS_URL"
    else
        echo "🌐 Access the FHIR server at: http://localhost"
        echo "   Note: If running on a remote server, use your server's external hostname or IP address"
    fi
    
    echo ""
    echo "📋 Useful commands:"
    echo "   cd $INSTALL_DIR"
    echo "   View logs:    $DOCKER_COMPOSE logs -f"
    echo "   Stop:         $DOCKER_COMPOSE down"
    echo "   Restart:      $DOCKER_COMPOSE restart"
    echo "   Status:       $DOCKER_COMPOSE ps"
else
    echo "❌ Error: Containers failed to start. Check logs with:"
    echo "   cd $INSTALL_DIR && $DOCKER_COMPOSE logs"
    exit 1
fi