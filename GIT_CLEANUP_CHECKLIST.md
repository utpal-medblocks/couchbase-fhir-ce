# Git Cleanup Checklist - Phase 3

## Files to DELETE from Git (Now Generated)

These files are now **generated** by `scripts/generate.py` and should be removed from git:

```bash
# Remove from git (but keep locally if you want)
git rm --cached docker-compose.yaml
git rm --cached docker-compose.yml
git rm --cached docker-compose.user.yml
git rm --cached haproxy.cfg

# Remove old template
rm config.yaml.template.old

# Remove working file
rm config.yaml.simple

# Commit the removals
git add .gitignore
git commit -m "Phase 3: Switch to generated docker-compose & haproxy from config.yaml.template"
```

## Files to KEEP in Git (Source Files)

✅ **Configuration:**
- `config.yaml.template` - Source of truth template

✅ **Generator:**
- `scripts/generate.py` - Generates docker-compose.yml & haproxy.cfg
- `scripts/apply-config.sh` - Applies config changes
- `scripts/build-generator-image.sh` - Builds generator Docker image
- `Dockerfile.generator` - Generator image definition

✅ **Documentation:**
- `docs/ZERO_FRICTION_SETUP.md`
- `docs/CONFIGURATION_GENERATOR.md`
- `PHASE3_CONFIG_GENERATOR_SUMMARY.md`

## What .gitignore Now Excludes

```gitignore
# User-specific config
config.yaml
config*.yaml

# Generated files
docker-compose.yml
docker-compose.yaml
haproxy.cfg
*.bak.*
config.yaml.template.old
```

## New User Workflow (After Cleanup)

Users clone the repo and see:

```
couchbase-fhir-ce/
├── config.yaml.template      ← Copy this to config.yaml
├── scripts/
│   ├── generate.py           ← Generator
│   ├── apply-config.sh       ← Apply changes
│   └── build-generator-image.sh
├── Dockerfile.generator
└── docs/
```

Then:
```bash
cp config.yaml.template config.yaml
vim config.yaml
./scripts/apply-config.sh  # Generates docker-compose.yml & haproxy.cfg
```

## Verification

After cleanup, `git status` should show:

```bash
$ git status
On branch main
Changes to be committed:
  modified:   .gitignore
  modified:   config.yaml.template
  new file:   Dockerfile.generator
  new file:   scripts/generate.py
  new file:   scripts/apply-config.sh
  new file:   scripts/build-generator-image.sh
  deleted:    docker-compose.yaml
  deleted:    haproxy.cfg
  deleted:    docker-compose.user.yml
```

✅ **Clean!** Only source files in git, generated files excluded.

## Quick Cleanup Script

```bash
#!/bin/bash
# Quick cleanup for Phase 3

echo "🧹 Cleaning up for Phase 3..."

# Remove generated files from git
git rm --cached docker-compose.yaml docker-compose.yml docker-compose.user.yml haproxy.cfg 2>/dev/null || true

# Remove old files
rm -f config.yaml.template.old config.yaml.simple

# Stage changes
git add .gitignore config.yaml.template scripts/ Dockerfile.generator docs/

echo "✅ Cleanup complete!"
echo ""
echo "Review with: git status"
echo "Commit with: git commit -m 'Phase 3: Config generator implementation'"
```

Save as `scripts/phase3-cleanup.sh` and run it!

