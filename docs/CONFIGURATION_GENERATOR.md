# Configuration Generator - Single Source of Truth

## Overview

All deployment configuration is now managed through **one file**: `config.yaml`

The generator reads `config.yaml` and produces:

- `docker-compose.yml` - Container orchestration
- `haproxy.cfg` - Reverse proxy configuration (if enabled)

## Quick Start

### 1. Initial Setup

```bash
# Copy template
cp config.yaml.template config.yaml

# Edit your settings
vim config.yaml

# Generate configuration files
python3 scripts/generate.py config.yaml

# Start services
docker-compose up -d
```

### 2. Change Configuration Later

```bash
# Edit config
vim config.yaml

# Apply changes (regenerates files + restarts services)
./scripts/apply-config.sh
```

That's it! **One command** to apply any configuration change.

## What Can You Configure?

### Application Settings

```yaml
app:
  baseUrl: "https://cbfhir.com/fhir" # Your public URL
  autoConnect: true
```

### TLS/HTTPS

```yaml
deploy:
  proxy:
    tls:
      enabled: true # ← Just toggle this!
      certPath: "./certs/cert.pem"
      keyPath: "./certs/key.pem"
```

### Ports

```yaml
deploy:
  proxy:
    listen:
      httpPort: 80 # Or 8080, 8000, etc.
      httpsPort: 443
```

### Resources

```yaml
deploy:
  fhirServer:
    resources:
      mem_limit: 3g # Adjust based on your server
      mem_reservation: 2g
```

### JVM Settings

```yaml
deploy:
  fhirServer:
    env:
      JAVA_TOOL_OPTIONS: >-
        -Xms1g
        -Xmx2g
        -XX:+UseG1GC
```

## Examples

### Example 1: Enable TLS

```yaml
# Before
deploy:
  proxy:
    tls:
      enabled: false

# After
deploy:
  proxy:
    tls:
      enabled: true
      certPath: "./certs/mycert.pem"
      keyPath: "./certs/mykey.pem"
```

```bash
./scripts/apply-config.sh
# ✅ TLS now enabled!
```

### Example 2: Change Ports

```yaml
# Before
deploy:
  proxy:
    listen:
      httpPort: 80
      httpsPort: 443

# After
deploy:
  proxy:
    listen:
      httpPort: 8080
      httpsPort: 8443
```

```bash
./scripts/apply-config.sh
# ✅ Now running on ports 8080/8443
```

### Example 3: Increase Memory

```yaml
# Before
deploy:
  fhirServer:
    resources:
      mem_limit: 3g
      mem_reservation: 2g

# After
deploy:
  fhirServer:
    resources:
      mem_limit: 6g
      mem_reservation: 4g
```

```bash
./scripts/apply-config.sh
# ✅ More memory allocated
```

## Files Generated

### docker-compose.yml

**⚠️ AUTO-GENERATED** - Do not edit manually!

- Container definitions
- Port mappings
- Volume mounts
- Resource limits
- Environment variables

### haproxy.cfg

**⚠️ AUTO-GENERATED** - Do not edit manually!

- TLS configuration
- Routing rules
- Backend definitions
- Timeouts

## Backup Strategy

Every time you run the generator, **backups are created**:

```
docker-compose.yml.bak.20251213_143022
haproxy.cfg.bak.20251213_143022
```

If something goes wrong, you can restore:

```bash
cp docker-compose.yml.bak.20251213_143022 docker-compose.yml
cp haproxy.cfg.bak.20251213_143022 haproxy.cfg
docker-compose restart
```

## Workflow

```
┌─────────────────┐
│  config.yaml    │  ← Edit this (single source of truth)
└────────┬────────┘
         │
         ↓
┌─────────────────────────────┐
│  ./scripts/apply-config.sh  │  ← Run this
└────────┬────────────────────┘
         │
         ├─→ Generates docker-compose.yml
         ├─→ Generates haproxy.cfg
         └─→ Restarts services

         ↓
    🎉 Done!
```

## Dependencies

- **Python 3**: Required for generator
- **PyYAML**: Python YAML parser

Install:

```bash
# Ubuntu/Debian
sudo apt install python3 python3-pip
python3 -m pip install pyyaml

# MacOS
brew install python3
pip3 install pyyaml
```

The `apply-config.sh` script will check and install PyYAML if missing.

## Troubleshooting

### Generator fails with Python error

```bash
# Check Python version (need 3.6+)
python3 --version

# Reinstall PyYAML
python3 -m pip install --upgrade pyyaml
```

### Services don't restart

```bash
# Manual restart
docker-compose down
docker-compose up -d

# Check logs
docker-compose logs -f
```

### Want to see what changed?

```bash
# Before applying
git diff config.yaml

# After generating (before restart)
git diff docker-compose.yml haproxy.cfg
```

## Advanced: No Proxy Mode

Don't want HAProxy? Set proxy type to `none`:

```yaml
deploy:
  proxy:
    type: "none" # Expose services directly
```

Services will expose ports directly:

- FHIR Server: Port 80 (or your httpPort)
- Admin UI: Port 3000

## Future Enhancements

Coming soon:

- `deploy.proxy.tls.mode: letsencrypt` - Auto SSL certificates
- `deploy.logging.centralized` - Log aggregation
- `deploy.monitoring` - Prometheus/Grafana
- Keycloak integration via config.yaml

## Summary

✅ **One file to edit**: config.yaml  
✅ **One command to apply**: ./scripts/apply-config.sh  
✅ **Automatic backups**: .bak files created  
✅ **Safe restarts**: Services updated smoothly

No more manual coordination of multiple files! 🎉
