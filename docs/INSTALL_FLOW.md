# Installation Flow - How It All Works

## Quick Install (One Command)

```bash
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml
```

## What Happens Behind the Scenes

### Step 1: Download `install.sh`

```
curl → GitHub → install.sh (downloaded to stdin)
```

- ✅ **Human-readable** bash script you can inspect
- ✅ **No hidden code** - everything is visible

### Step 2: Install Script Execution

```
install.sh does:
1. Check Docker & Docker Compose installed
2. Pull couchbase/fhir-generator:latest (pre-built image)
3. Generate docker-compose.yml + haproxy.cfg from config.yaml
4. Pull ghcr.io/couchbaselabs/fhir-server:latest
5. Pull ghcr.io/couchbaselabs/fhir-admin:latest
6. Start services with docker compose up -d
```

### Step 3: Generator Image

**What is `couchbase/fhir-generator`?**

- Lightweight Python image (Python 3.9 + PyYAML)
- Contains only `generate.py` script
- **No external code execution** - just YAML parsing
- Generates config files deterministically

**Dockerfile.generator:**

```dockerfile
FROM python:3.9-slim
RUN pip install --no-cache-dir pyyaml
COPY scripts/generate.py /usr/local/bin/generate.py
WORKDIR /work
ENTRYPOINT ["python", "/usr/local/bin/generate.py"]
```

### Step 4: Config Generation

```
config.yaml → [Generator] → docker-compose.yml + haproxy.cfg
```

**What it reads:**

- `app.baseUrl` → HAProxy routing
- `couchbase.connection` → Environment variables
- `deploy.jvm` → JVM memory settings
- `deploy.tls` → HTTPS configuration
- `logging` → Log levels

**What it writes:**

- `docker-compose.yml` - Service definitions, ports, volumes, memory limits
- `haproxy.cfg` - Load balancer routing, TLS config, stats endpoint

### Step 5: Pull Pre-Built Images

```
GitHub Container Registry (ghcr.io)
├── couchbaselabs/fhir-server:latest   (Spring Boot backend)
└── couchbaselabs/fhir-admin:latest    (React frontend)
```

**No local builds** - images are pre-built in CI/CD and verified.

### Step 6: Start Services

```
docker compose up -d
├── fhir-server   (backend on port 8080)
├── fhir-admin    (frontend on port 80)
└── haproxy       (load balancer on ports 80/443)
```

## Local Development (With Building)

```bash
# Clone repo
git clone https://github.com/couchbaselabs/couchbase-fhir-ce.git
cd couchbase-fhir-ce

# Edit config
cp config.yaml.template config.yaml
vim config.yaml

# Apply config (generates + builds + starts)
./scripts/apply-config.sh ./config.yaml
```

**Difference:** This builds images locally from source instead of pulling pre-built ones.

## File Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│ User provides: config.yaml                                  │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
      ┌───────────────────────────┐
      │  couchbase/fhir-generator │  ← Pre-built Docker image
      │  (Python + PyYAML)        │     (no external code exec)
      └───────────┬───────────────┘
                  │ runs generate.py
                  ▼
          ┌───────────────┐
          │ Generated:    │
          │ ├── docker-   │
          │ │   compose.  │
          │ │   yml       │
          │ └── haproxy.  │
          │     cfg       │
          └───────┬───────┘
                  │
                  ▼
      ┌───────────────────────────┐
      │ Docker Compose pulls:     │
      │ ├── ghcr.io/.../fhir-     │
      │ │   server:latest         │
      │ └── ghcr.io/.../fhir-     │
      │     admin:latest           │
      └───────────┬───────────────┘
                  │
                  ▼
          ┌───────────────┐
          │ Services:     │
          │ ├── backend   │
          │ ├── frontend  │
          │ └── haproxy   │
          └───────────────┘
```

## Security Considerations

### ✅ What is Safe

1. **install.sh** - Human-readable bash script, no obfuscation
2. **Generator image** - Only contains Python + PyYAML + generate.py
3. **Pre-built images** - Built from public GitHub repo with verifiable CI/CD
4. **No hidden downloads** - Everything comes from official repos

### ⚠️ What to Verify

1. **config.yaml** - Contains your Couchbase credentials

   - Store securely, don't commit to public repos
   - Use environment-specific configs

2. **Generated files** - Review before first use:

   ```bash
   cat docker-compose.yml
   cat haproxy.cfg
   ```

3. **Image sources** - All from `ghcr.io/couchbaselabs/*`
   - Verify with: `docker image inspect <image>`

## Updating

### Update Config Only (No Rebuild)

```bash
vim config.yaml
./scripts/apply-config.sh ./config.yaml
```

Regenerates config files and restarts services.

### Update Images (Pull Latest)

```bash
docker compose pull
docker compose up -d
```

### Update Everything (Rebuild from Source)

```bash
git pull
./scripts/apply-config.sh ./config.yaml --build
```

## Cache Optimization

### Backend Build Cache

The `backend/Dockerfile` now uses multi-stage caching:

```dockerfile
# Layer 1: Download dependencies (cached)
COPY pom.xml .
RUN ./mvnw dependency:go-offline

# Layer 2: Build app (only rebuilds if source changes)
COPY src ./src
RUN ./mvnw package -o
```

**Result:** Dependencies download only once, subsequent builds are fast!

### Frontend Build Cache

```dockerfile
# Layer 1: Install npm packages (cached)
COPY package.json package-lock.json ./
RUN npm ci

# Layer 2: Build app (only rebuilds if source changes)
COPY . .
RUN npm run build
```

## Troubleshooting

### "Repository does not exist" Error

**Old behavior:** Tries to pull non-existent images  
**Fixed:** Generator now includes `build` contexts for local dev

### "Exit code 132" During Build

**Cause:** npm ci failing (SIGILL - CPU/memory issue)  
**Fixed:** Updated frontend Dockerfile with `--maxsockets=1 --prefer-offline`

### "HAProxy metrics unavailable"

**Cause:** Wrong stats endpoint URL  
**Fixed:** Changed from `/stats;csv` to `/haproxy?stats;csv`

### Slow Builds Every Time

**Cause:** No Docker layer caching  
**Fixed:** Updated Dockerfiles to cache dependencies separately from source

## What Gets Downloaded?

### First-Time Install (via install.sh)

1. `install.sh` script (~8KB)
2. `couchbase/fhir-generator:latest` (~150MB) - cached for future use
3. `ghcr.io/couchbaselabs/fhir-server:latest` (~400MB)
4. `ghcr.io/couchbaselabs/fhir-admin:latest` (~50MB)
5. `haproxy:2.8-alpine` (~15MB)

**Total:** ~615MB (one-time, then cached)

### Local Development Build

1. Maven dependencies (~200MB) - cached
2. npm dependencies (~300MB) - cached
3. Base images (Java, Node, Nginx) - cached

**Total:** ~500MB (cached after first build)

## FAQ

**Q: Does it download executable code?**  
A: Only Docker images from verified sources (GitHub Container Registry)

**Q: Can I use my own images?**  
A: Yes! Edit docker-compose.yml to point to your registry

**Q: Where is my data stored?**  
A: In Couchbase (configured in config.yaml), not in Docker volumes

**Q: What if I don't have internet?**  
A: Build locally from source after initial clone

**Q: Can I use this in production?**  
A: Yes, with proper TLS and security hardening (see docs/PRODUCTION.md)
