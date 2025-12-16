# Docker Build Guide

## Quick Reference

### Generate Config and Build Everything

```bash
# One command to rule them all:
./scripts/apply-config.sh ./config.yaml
```

This will:

1. ✅ Generate `docker-compose.yml` from `config.yaml`
2. ✅ Generate `haproxy.cfg` from `config.yaml`
3. ✅ Build Docker images (`fhir-server`, `fhir-admin`)
4. ✅ Start/restart services

### Manual Build (if needed)

```bash
# Generate config files only (no build/restart)
docker run --rm -v $(pwd):/work -w /work couchbase/fhir-generator:latest python scripts/generate.py config.yaml

# Build specific service
docker compose build fhir-server
docker compose build fhir-admin

# Build with no cache (fresh build)
docker compose build --no-cache

# Build and start
docker compose up -d --build
```

## Troubleshooting

### Issue: "repository does not exist or may require 'docker login'"

**Problem:** Docker tries to pull images from Docker Hub, but they don't exist there.

**Solution:** The generator now includes `build` contexts automatically. Just run:

```bash
./scripts/apply-config.sh ./config.yaml
```

### Issue: "Exit code 132" during npm ci

**Problem:** `SIGILL` - usually CPU architecture mismatch or memory issue.

**Solutions:**

1. **Multi-platform builds**: Specify target platform

   ```bash
   docker compose build --platform linux/amd64
   ```

2. **Memory**: Increase Docker memory (Docker Desktop → Settings → Resources)

   - Recommended: 4GB minimum

3. **Frontend Dockerfile** already updated with:
   ```dockerfile
   RUN npm ci --maxsockets=1 --prefer-offline --no-audit
   ```

### Issue: Maven dependency resolution failure

**Problem:** Network issues or Maven cache corruption in Docker.

**Solutions:**

1. **Clear Docker build cache:**

   ```bash
   docker compose build --no-cache fhir-server
   ```

2. **Force Maven re-download:**

   ```bash
   docker compose build --no-cache --pull fhir-server
   ```

3. **Check local build works:**
   ```bash
   cd backend && ./mvnw clean package -DskipTests
   ```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Build and Push Images

on:
  push:
    branches: [main, master]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Build Generator Image
        run: docker build -f Dockerfile.generator -t couchbase/fhir-generator:latest .

      - name: Generate docker-compose.yml
        run: |
          docker run --rm \
            -v $(pwd):/work \
            -w /work \
            couchbase/fhir-generator:latest \
            python scripts/generate.py config.yaml

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v2

      - name: Build Images
        run: docker compose build

      - name: Login to Docker Hub
        uses: docker/login-action@v2
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Push Images
        run: docker compose push
```

## Development Workflow

### Local Development

```bash
# 1. Edit config.yaml
vim config.yaml

# 2. Apply changes (builds + restarts)
./scripts/apply-config.sh ./config.yaml

# 3. View logs
docker compose logs -f

# 4. Stop services
docker compose down
```

### Production Deployment

```bash
# 1. Pull latest code
git pull

# 2. Update config for production
vim config.yaml

# 3. Build and deploy
./scripts/apply-config.sh ./config.yaml

# 4. Verify
docker compose ps
docker compose logs -f fhir-server
```

## Generated Files

These files are auto-generated from `config.yaml`:

- ✅ `docker-compose.yml` - Service definitions with build contexts
- ✅ `haproxy.cfg` - Load balancer configuration

**Do not edit these files directly** - they will be overwritten!

## Architecture

```
config.yaml (source of truth)
    ↓
generate.py (reads config)
    ↓
├── docker-compose.yml (with build contexts)
└── haproxy.cfg
    ↓
docker compose build (builds images)
    ↓
├── couchbase/fhir-server:latest
└── couchbase/fhir-admin:latest
    ↓
docker compose up (starts services)
```

## Tips

- **First time setup**: Generation + build takes 5-10 minutes
- **Subsequent builds**: Docker layer caching makes it faster (1-2 mins)
- **Config changes only**: No rebuild needed if you only change ports/memory/env vars
- **Code changes**: Run `./scripts/apply-config.sh` to rebuild

## Common Commands

```bash
# View running services
docker compose ps

# View logs (all services)
docker compose logs -f

# View logs (specific service)
docker compose logs -f fhir-server

# Restart a service
docker compose restart fhir-server

# Stop everything
docker compose down

# Remove everything (including volumes)
docker compose down -v

# Check resource usage
docker stats
```
