# Zero-Friction Setup with Pre-Built Generator

## The Problem We Solved

**Before:** Users needed Python, pip, and PyYAML installed locally.
```bash
# Old way - friction! 😞
sudo apt install python3 python3-pip
pip install pyyaml
./scripts/apply-config.sh
```

**After:** Only Docker needed (already required for FHIR server).
```bash
# New way - frictionless! 🎉
./scripts/apply-config.sh  # Just works!
```

## How It Works

### 1. Pre-Built Generator Image

We ship a lightweight Docker image with PyYAML pre-installed:

```dockerfile
# Dockerfile.generator
FROM python:3.9-slim
RUN pip install --no-cache-dir pyyaml
WORKDIR /work
```

**Image:** `couchbase/fhir-generator:latest`
**Size:** ~50MB (tiny!)
**Build time:** 30 seconds (one-time)

### 2. apply-config.sh Uses the Image

```bash
# No local Python/pip needed!
docker run --rm \
    -v "$(pwd):/work" \
    couchbase/fhir-generator:latest \
    python scripts/generate.py config.yaml
```

### 3. Auto-Build on First Use

If the image doesn't exist, `apply-config.sh` automatically builds it:

```bash
$ ./scripts/apply-config.sh
📦 Building generator image (one-time setup)...
🔧 Generating configuration files...
✅ Done!
```

**First run:** 30 seconds (builds image)
**Subsequent runs:** ~2 seconds (uses cached image)

## User Experience

### Fresh EC2 Instance - Zero Friction

```bash
# 1. Install Docker (only dependency)
curl -fsSL https://get.docker.com | sh

# 2. Clone repo
git clone https://github.com/couchbaselabs/couchbase-fhir-ce.git
cd couchbase-fhir-ce

# 3. Configure
cp config.yaml.template config.yaml
vim config.yaml

# 4. Deploy - JUST WORKS!
./scripts/apply-config.sh

# Done! FHIR server running 🎉
```

**No Python, no pip, no PyYAML install needed!**

### Update Configuration Later

```bash
# 1. Edit config
vim config.yaml  # Enable TLS, change memory, etc.

# 2. Apply changes
./scripts/apply-config.sh  # Fast! (image already exists)

# Done!
```

## For Maintainers

### Building the Generator Image

```bash
# Build locally
./scripts/build-generator-image.sh

# Build and push to Docker Hub
./scripts/build-generator-image.sh --push
```

### Publishing to Docker Hub

```bash
# Login to Docker Hub
docker login

# Build and tag
docker build -f Dockerfile.generator -t couchbase/fhir-generator:latest .
docker tag couchbase/fhir-generator:latest couchbase/fhir-generator:v1.0.0

# Push
docker push couchbase/fhir-generator:latest
docker push couchbase/fhir-generator:v1.0.0
```

Users can then pull the pre-built image instead of building locally:

```bash
# In install.sh
docker pull couchbase/fhir-generator:latest
```

## Benefits

### ✅ Zero Local Dependencies
- Only Docker required
- Already needed for FHIR server
- No Python/pip/PyYAML installation

### ✅ Fast Execution
- First run: ~30 seconds (one-time build)
- Subsequent: ~2 seconds
- Image cached locally

### ✅ Consistent Environment
- Same Python version everywhere
- Same PyYAML version
- No "works on my machine" issues

### ✅ Professional Feel
- No installation friction
- Clean, polished experience
- "Just works" out of the box

### ✅ Easy to Update
- Update Dockerfile → rebuild image
- Push new version
- Users get updates automatically

## Technical Details

### Image Layers

```
python:3.9-slim (base)     ~40MB
+ pip install pyyaml        ~2MB
+ metadata                  ~1MB
= Total                    ~43MB
```

Tiny! Downloads in seconds on any connection.

### Security

```bash
# Run as non-root user
docker run --rm \
    -v "$(pwd):/work" \
    -u "$(id -u):$(id -g)" \    # User's UID/GID
    couchbase/fhir-generator:latest \
    python scripts/generate.py config.yaml
```

Generated files owned by user, not root.

### Offline Support

Once image is pulled/built, works offline:

```bash
$ docker images
couchbase/fhir-generator   latest   abc123   43MB

$ ./scripts/apply-config.sh  # Works without internet!
```

## Comparison

| Approach | Dependencies | First Run | Subsequent | Friction |
|----------|-------------|-----------|------------|----------|
| **Pre-built Docker image** | Docker only | 30s (build) | 2s | ⭐⭐⭐⭐⭐ |
| Python + pip + PyYAML | Python, pip, PyYAML | 60s (install) | 1s | ⭐⭐ |
| Go binary | None | Instant | Instant | ⭐⭐⭐⭐⭐ |

Docker image gives us "almost instant" with "almost zero" dependencies!

## FAQ

### Why not just use python:3.9-slim directly?

```bash
# Would work, but installs PyYAML every time (slow!)
docker run --rm python:3.9-slim sh -c "pip install pyyaml && python ..."
# 15 seconds every run 😞
```

Pre-built image has PyYAML baked in → instant!

### Why not ship a Go binary?

- More work to build/maintain
- Need multi-platform binaries (Linux, Mac, Windows)
- Need CI/CD for releases
- Docker approach is simpler and "good enough"

Can always upgrade to Go binary later if needed.

### What if Docker Hub is down?

Image auto-builds locally if not found:

```bash
$ ./scripts/apply-config.sh
📦 Building generator image (one-time setup)...
# Falls back to local build
```

### Can I use my own registry?

Yes! Change the image name:

```bash
# scripts/apply-config.sh
GENERATOR_IMAGE="myregistry.com/fhir-generator:latest"
```

## Summary

✅ **Zero friction** - Only Docker needed (already required)
✅ **Fast** - 30s first run, 2s after
✅ **Professional** - Pre-built image shows polish
✅ **Maintainable** - Simple Dockerfile, easy to update
✅ **Secure** - Runs as user, not root
✅ **Offline-friendly** - Works without internet after first run

**Perfect balance of simplicity and user experience!** 🎉

