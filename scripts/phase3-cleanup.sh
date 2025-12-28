#!/bin/bash
# =============================================================================
# Phase 3 Git Cleanup
# =============================================================================
# Removes generated files from git and stages Phase 3 changes
# =============================================================================

echo "🧹 Phase 3: Cleaning up git repository..."
echo ""

# Remove generated files from git (keep locally)
echo "📦 Removing generated files from git..."
git rm --cached docker-compose.yaml 2>/dev/null || true
git rm --cached docker-compose.yml 2>/dev/null || true
git rm --cached docker-compose.user.yml 2>/dev/null || true
git rm --cached haproxy.cfg 2>/dev/null || true

# Remove old working files
echo "🗑️  Removing old files..."
rm -f config.yaml.template.old
rm -f config.yaml.simple

# Stage Phase 3 additions
echo "➕ Staging Phase 3 files..."
git add .gitignore
git add config.yaml.template
git add scripts/generate.py
git add scripts/apply-config.sh
git add scripts/build-generator-image.sh
git add Dockerfile.generator
git add docs/ZERO_FRICTION_SETUP.md
git add PHASE3_CONFIG_GENERATOR_SUMMARY.md
git add GIT_CLEANUP_CHECKLIST.md

echo ""
echo "✅ Cleanup complete!"
echo ""
echo "📊 Review changes:"
echo "   git status"
echo "   git diff --cached"
echo ""
echo "💾 Commit when ready:"
echo "   git commit -m 'Phase 3: Single source of truth config with Docker-based generator'"

