#!/bin/bash

# ============================================================================
# Couchbase FHIR CE One-Line Installer
# ============================================================================
# 
# This script downloads and runs the Couchbase FHIR CE platform using Docker.
# It requires a config.yaml file to customize the installation.
#
# Usage:
#   curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml
#
# What this script does:
#   1. Downloads config.yaml.template if no config provided
#   2. Pulls couchbase/fhir-generator image (for config generation)
#   3. Generates docker-compose.yml and haproxy.cfg from config.yaml
#   4. Pulls pre-built FHIR server images from GitHub Container Registry
#   5. Starts the services
#
# No executable code from external sources is run - only Docker images.
# ============================================================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

CONFIG_FILE="$1"
INSTALL_DIR="couchbase-fhir-ce"
GENERATOR_IMAGE="ghcr.io/couchbaselabs/couchbase-fhir-ce/fhir-generator:latest"

echo -e "${BLUE}🚀 Couchbase FHIR CE Installer${NC}"
echo ""

# ============================================================================
# Pre-flight Checks
# ============================================================================

# Check Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Error: Docker is not installed.${NC}"
    echo "   Please install Docker first: https://docs.docker.com/get-docker/"
    exit 1
fi

# Check Docker Compose
if command -v docker-compose &> /dev/null; then
    DOCKER_COMPOSE="docker-compose"
elif docker compose version &> /dev/null 2>&1; then
    DOCKER_COMPOSE="docker compose"
else
    echo -e "${RED}❌ Error: Docker Compose is not installed.${NC}"
    echo "   Please install Docker Compose: https://docs.docker.com/compose/install/"
    exit 1
fi

echo -e "${GREEN}✅ Using: $DOCKER_COMPOSE${NC}"

# ============================================================================
# Config File Handling
# ============================================================================

if [ -z "$CONFIG_FILE" ]; then
    echo -e "${YELLOW}⚠️  No config file provided. Downloading template...${NC}"
    CONFIG_FILE="config.yaml"
    curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/config.yaml.template -o "$CONFIG_FILE"
    echo -e "${GREEN}✅ Downloaded: $CONFIG_FILE${NC}"
    echo ""
    echo -e "${YELLOW}📝 Please edit $CONFIG_FILE with your settings before continuing.${NC}"
    echo "   Key settings to configure:"
    echo "   - couchbase.connection (server, username, password)"
    echo "   - admin.email and admin.password"
    echo "   - app.baseUrl (if not localhost)"
    echo ""
    echo "After editing, run this command again:"
    echo "  curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- $CONFIG_FILE"
    exit 0
fi

if [ ! -f "$CONFIG_FILE" ]; then
    echo -e "${RED}❌ Error: Config file '$CONFIG_FILE' not found${NC}"
    echo "   Download template: curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/config.yaml.template -o config.yaml"
    exit 1
fi

echo -e "${GREEN}✅ Using config file: $CONFIG_FILE${NC}"

# ============================================================================
# Create Installation Directory
# ============================================================================

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR"
echo -e "${GREEN}📁 Installation directory: $(pwd)${NC}"

# Copy config file
cp "../$CONFIG_FILE" config.yaml 2>/dev/null || cp "$CONFIG_FILE" config.yaml

# Create logs directory with proper permissions
if [ ! -d logs ]; then
    echo "📁 Creating logs directory..."
    mkdir -p logs
    chmod 0777 logs 2>/dev/null || true
fi

# ============================================================================
# Pull Generator Image
# ============================================================================

echo ""
echo -e "${BLUE}📦 Pulling configuration generator...${NC}"
docker pull "$GENERATOR_IMAGE"

# ============================================================================
# Generate Config Files
# ============================================================================

echo ""
echo -e "${BLUE}🔧 Generating docker-compose.yml and haproxy.cfg...${NC}"
docker run --rm \
    -v "$(pwd):/work" \
    -w /work \
    "$GENERATOR_IMAGE" \
    config.yaml

if [ ! -f docker-compose.yml ]; then
    echo -e "${RED}❌ Error: Failed to generate docker-compose.yml${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Configuration files generated${NC}"

# ============================================================================
# Download Scripts for Management
# ============================================================================

echo ""
echo -e "${BLUE}📥 Downloading management scripts...${NC}"
mkdir -p scripts
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/scripts/apply-config.sh -o scripts/apply-config.sh
chmod +x scripts/apply-config.sh
echo -e "${GREEN}✅ Scripts downloaded${NC}"

# ============================================================================
# Pull Pre-Built Images
# ============================================================================

echo ""
echo -e "${BLUE}📦 Pulling FHIR server images...${NC}"
$DOCKER_COMPOSE pull

# ============================================================================
# Start Services
# ============================================================================

echo ""
echo -e "${BLUE}🚀 Starting services...${NC}"

# Stop existing containers
$DOCKER_COMPOSE down 2>/dev/null || true

# Start new containers
$DOCKER_COMPOSE up -d

# Wait for services to be ready
echo "⏳ Waiting for services to start..."
sleep 5

# ============================================================================
# Verify and Display Access Info
# ============================================================================

if $DOCKER_COMPOSE ps | grep -q "Up"; then
    echo ""
    echo -e "${GREEN}✅ Couchbase FHIR CE is now running!${NC}"
    echo ""
    
    # Extract HTTP port from docker-compose.yml
    HTTP_PORT=$(grep -E "^\s+- \"[0-9]+:80\"" docker-compose.yml | sed -E 's/.*"([0-9]+):80".*/\1/' || echo "80")
    PORT_SUFFIX=""
    [ "$HTTP_PORT" != "80" ] && PORT_SUFFIX=":$HTTP_PORT"
    
    # Auto-detect access URL
    ACCESS_URL=""
    
    # Try AWS EC2 metadata (IMDSv2)
    if command -v curl &> /dev/null; then
        AWS_TOKEN=$(curl -s --max-time 2 -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600" 2>/dev/null || echo "")
        if [ -n "$AWS_TOKEN" ]; then
            AWS_HOSTNAME=$(curl -s --max-time 2 -H "X-aws-ec2-metadata-token: $AWS_TOKEN" http://169.254.169.254/latest/meta-data/public-hostname 2>/dev/null || echo "")
            [ -n "$AWS_HOSTNAME" ] && ACCESS_URL="http://$AWS_HOSTNAME$PORT_SUFFIX"
        fi
    fi
    
    # Try GCP metadata
    if [ -z "$ACCESS_URL" ] && command -v curl &> /dev/null; then
        GCP_IP=$(curl -s --max-time 2 -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip 2>/dev/null || echo "")
        [ -n "$GCP_IP" ] && ACCESS_URL="http://$GCP_IP$PORT_SUFFIX"
    fi
    
    # Try Azure metadata
    if [ -z "$ACCESS_URL" ] && command -v curl &> /dev/null; then
        AZURE_IP=$(curl -s --max-time 2 -H "Metadata:true" "http://169.254.169.254/metadata/instance/network/interface/0/ipv4/ipAddress/0/publicIpAddress?api-version=2021-02-01&format=text" 2>/dev/null || echo "")
        [ -n "$AZURE_IP" ] && ACCESS_URL="http://$AZURE_IP$PORT_SUFFIX"
    fi
    
    # Display access URL
    if [ -n "$ACCESS_URL" ]; then
        echo -e "${GREEN}🌐 Access URL: $ACCESS_URL${NC}"
    else
        echo -e "${GREEN}🌐 Access URL: http://localhost$PORT_SUFFIX${NC}"
        echo -e "${YELLOW}   (Use your server's external hostname/IP if running remotely)${NC}"
    fi
    
    echo ""
    echo -e "${BLUE}📋 Useful Commands:${NC}"
    echo "   cd $INSTALL_DIR"
    echo "   View logs:    docker compose logs -f"
    echo "   Stop:         docker compose down"
    echo "   Restart:      docker compose restart"
    echo "   Status:       docker compose ps"
    echo "   Update:       Edit config.yaml, then: ./scripts/apply-config.sh config.yaml"
    echo ""
    echo -e "${BLUE}📚 Documentation:${NC}"
    echo "   https://fhir.couchbase.com/docs/intro"
    
else
    echo ""
    echo -e "${RED}❌ Error: Services failed to start${NC}"
    echo "   Check logs: cd $INSTALL_DIR && docker compose logs"
    exit 1
fi
