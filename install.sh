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

# ---------------------------------------------------------------------------
# Ensure logs directory exists BEFORE starting containers.
# If the host path does not exist when Docker creates the bind mount, Docker
# (running as root) will create it owned by root:root with 755 perms. The
# fhir-server image runs as an unprivileged user and then cannot write GC/JFR
# logs -> JVM startup failure (permission denied on /app/logs/gc.log).
# We proactively create it here with permissive write access so the container
# user (unknown UID inside image) can write even if ownership differs.
# ---------------------------------------------------------------------------
if [ ! -d logs ]; then
    echo "📁 Creating logs directory (host bind mount target)..."
    mkdir -p logs
fi

# Attempt to adjust ownership if created previously by root and we know the
# invoking user's original UID (sudo sets SUDO_UID / SUDO_GID). Even if this
# fails, the chmod below will still allow writes.
OWNER=$(ls -ld logs | awk '{print $3}') || OWNER="unknown"
if [ "$OWNER" = "root" ]; then
    if [ -n "$SUDO_UID" ] && [ -n "$SUDO_GID" ]; then
        chown "$SUDO_UID":"$SUDO_GID" logs 2>/dev/null || true
    fi
fi

# Grant broad write permissions. 0775 is preferred; if that fails (e.g. due to
# restrictive umask or ownership) fall back to 0777. These logs may contain
# diagnostic information; if you need stricter security, manually adjust perms
# and rebuild the image with an init entrypoint that fixes ownership.
chmod 0775 logs 2>/dev/null || chmod 0777 logs 2>/dev/null || true

# Verify file integrity
echo "🔐 Verifying file integrity..."
if command -v sha256sum &> /dev/null; then
    # Create temporary checksums for downloaded files only (auto-generated values)
    echo "a6623dc15b2b1cb2504e0f393d2c605b77e00a751a6712978ddccc11f2b508ec  docker-compose.yml" > temp_checksums.txt
    echo "75436ec98b8f55133edb9faff48264f88274e88c73c2e0f1d908b68cdc62098b  haproxy.cfg" >> temp_checksums.txt
    
    if ! sha256sum -c temp_checksums.txt --quiet 2>/dev/null; then
        echo "❌ Warning: File integrity verification failed. Files may have been tampered with."
        echo "   Proceeding anyway, but please report this issue."
    else
        echo "✅ File integrity verified"
    fi
    rm -f temp_checksums.txt
elif command -v shasum &> /dev/null; then
    # macOS fallback (same hashes as above)
    echo "a6623dc15b2b1cb2504e0f393d2c605b77e00a751a6712978ddccc11f2b508ec  docker-compose.yml" > temp_checksums.txt
    echo "75436ec98b8f55133edb9faff48264f88274e88c73c2e0f1d908b68cdc62098b  haproxy.cfg" >> temp_checksums.txt
    
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
    
    # Try AWS EC2 metadata (IMDSv2)
    if command -v curl &> /dev/null; then
        # Get token first (IMDSv2 requirement)
        AWS_TOKEN=$(curl -s --max-time 3 --connect-timeout 2 -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null || echo "")
        if [ -n "$AWS_TOKEN" ]; then
            AWS_HOSTNAME=$(curl -s --max-time 3 --connect-timeout 2 -H "X-aws-ec2-metadata-token: $AWS_TOKEN" http://169.254.169.254/latest/meta-data/public-hostname 2>/dev/null || echo "")
            if [ -n "$AWS_HOSTNAME" ] && [ "$AWS_HOSTNAME" != "" ]; then
                ACCESS_URL="http://$AWS_HOSTNAME"
            fi
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