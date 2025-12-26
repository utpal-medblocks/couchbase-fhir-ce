#!/usr/bin/env python3
"""
Remove Keycloak service from docker-compose*.yml files under a given directory.

Usage: remove_keycloak_compose.py <root_dir>
"""
import sys
import os
import glob

try:
    import yaml
except Exception as e:
    print("PyYAML is required to run this script (pip install pyyaml)", file=sys.stderr)
    raise


def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    try:
        data = yaml.safe_load(content) or {}
    except Exception as exc:
        print(f"Failed to parse YAML file {path}: {exc}", file=sys.stderr)
        return False

    if not isinstance(data, dict):
        return True

    services = data.get('services')
    if not services or 'keycloak' not in services:
        print(f"No Keycloak service in {path}")
        return True

    # Remove keycloak service
    services.pop('keycloak', None)

    # Remove depends_on reference to keycloak in fhir-server if present
    fhir = services.get('fhir-server') or services.get('fhir_server')
    if fhir and isinstance(fhir, dict):
        deps = fhir.get('depends_on')
        if isinstance(deps, list) and 'keycloak' in deps:
            deps = [d for d in deps if d != 'keycloak']
            if deps:
                fhir['depends_on'] = deps
            else:
                fhir.pop('depends_on', None)

    # Backup original
    try:
        with open(path + '.bak.remove', 'w', encoding='utf-8') as b:
            b.write(content)
    except Exception:
        print(f"Warning: could not write backup for {path}", file=sys.stderr)

    # Write YAML back
    try:
        with open(path, 'w', encoding='utf-8') as f:
            yaml.safe_dump(data, f, default_flow_style=False, sort_keys=False)
    except Exception as exc:
        print(f"Failed to write updated YAML to {path}: {exc}", file=sys.stderr)
        return False

    print(f"Removed Keycloak from {path}")
    return True


def main(root_dir: str):
    patterns = [os.path.join(root_dir, 'docker-compose*.yml'), os.path.join(root_dir, 'docker-compose*.yaml')]
    files = []
    for p in patterns:
        files.extend(glob.glob(p))
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
        print('Usage: remove_keycloak_compose.py <root_dir>', file=sys.stderr)
        sys.exit(2)
    root = sys.argv[1]
    sys.exit(main(root))
