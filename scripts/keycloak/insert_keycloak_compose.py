#!/usr/bin/env python3
"""
Insert a Keycloak service into all docker-compose*.yml files under a given directory.

This uses PyYAML to properly parse and write YAML so blank lines or unusual
formatting around `services:` won't prevent insertion.

Usage: insert_keycloak_compose.py <root_dir>
"""
import sys
import os
import glob

try:
    import yaml
except Exception as e:
    print("PyYAML is required to run this script (pip install pyyaml)", file=sys.stderr)
    raise


KEYCLOAK_SERVICE = {
    'image': 'quay.io/keycloak/keycloak:24.0.1',
    'environment': {
        'KEYCLOAK_ADMIN': '${KEYCLOAK_ADMIN_USERNAME}',
        'KEYCLOAK_ADMIN_PASSWORD': '${KEYCLOAK_ADMIN_PASSWORD}',
        'KC_IMPORT': 'true',
        'KC_HEALTH_ENABLED': 'true',
    },
    'ports': ['8081:8080'],
    'command': 'start-dev --http-relative-path=/auth',
    'restart': 'unless-stopped',
    'volumes': ['./scripts/keycloak/realm.json:/opt/keycloak/data/import/realm.json:ro'],
}


def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()

    try:
        data = yaml.safe_load(content) or {}
    except Exception as exc:
        print(f"Failed to parse YAML file {path}: {exc}", file=sys.stderr)
        return False

    if not isinstance(data, dict):
        data = {}

    services = data.get('services')
    if services is None:
        services = {}
        data['services'] = services

    if 'keycloak' in services:
        print(f"Keycloak already present in {path}")
        return True

    # Insert keycloak service
    services['keycloak'] = KEYCLOAK_SERVICE

    # Backup original
    try:
        with open(path + '.bak', 'w', encoding='utf-8') as b:
            b.write(content)
    except Exception:
        print(f"Warning: could not write backup for {path}", file=sys.stderr)

    # Write YAML back with reasonable formatting
    try:
        with open(path, 'w', encoding='utf-8') as f:
            yaml.safe_dump(data, f, default_flow_style=False, sort_keys=False)
    except Exception as exc:
        print(f"Failed to write updated YAML to {path}: {exc}", file=sys.stderr)
        return False

    print(f"Inserted Keycloak into {path}")
    return True


def main(root_dir: str):
    patterns = [os.path.join(root_dir, 'docker-compose*.yml'), os.path.join(root_dir, 'docker-compose*.yaml')]
    files = []
    for p in patterns:
        files.extend(glob.glob(p))
    # deduplicate and sort for stable behaviour
    files = sorted(set(files))
    if not files:
        print(f"No docker-compose*.yml files found in {root_dir}", file=sys.stderr)
        return 2

    rc = 0
    for f in files:
        ok = process_file(f)
        if not ok:
            rc = 1
    return rc


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print('Usage: insert_keycloak_compose.py <root_dir>', file=sys.stderr)
        sys.exit(2)
    root = sys.argv[1]
    sys.exit(main(root))
