#!/usr/bin/env python3
"""
Patch backend application.yml to set Keycloak-related properties with concrete values.

Usage: patch_application_yml.py <path/to/application.yml> <use_keycloak:true|false> <jwks_uri>

This will create a backup at application.yml.bak (if not present) and merge the following keys:
  app.security.use-keycloak: <true|false>
  spring.security.oauth2.resourceserver.jwt.jwk-set-uri: <jwks_uri>

The script uses PyYAML to preserve YAML structure.
"""
import sys
import os

try:
    import yaml
except Exception:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    raise


def ensure_path(dct, keys):
    cur = dct
    for k in keys:
        if k not in cur or not isinstance(cur[k], dict):
            cur[k] = {}
        cur = cur[k]
    return cur


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(2)

    path = sys.argv[1]
    use_keycloak_str = sys.argv[2]
    jwks_uri = sys.argv[3]

    use_keycloak = False
    if str(use_keycloak_str).lower() in ("true", "1", "yes"):
        use_keycloak = True

    if not os.path.isfile(path):
        print(f"application.yml not found at {path}", file=sys.stderr)
        sys.exit(1)

    # backup
    bak = path + '.bak'
    if not os.path.isfile(bak):
        try:
            with open(path, 'r', encoding='utf-8') as f:
                orig = f.read()
            with open(bak, 'w', encoding='utf-8') as f:
                f.write(orig)
        except Exception as e:
            print(f"Warning: could not write backup: {e}", file=sys.stderr)

    # load all documents (support multi-document YAML)
    with open(path, 'r', encoding='utf-8') as f:
        docs = list(yaml.safe_load_all(f))

    if not docs:
        docs = [{}]

    # Prefer to update the first document (most app-level properties live there)
    data = docs[0] if isinstance(docs[0], dict) else {}

    # set app.security.use-keycloak
    appsec = ensure_path(data, ['app', 'security'])
    appsec['use-keycloak'] = bool(use_keycloak)

    # set spring.security.oauth2.resourceserver.jwt.jwk-set-uri
    jwt = ensure_path(data, ['spring', 'security', 'oauth2', 'resourceserver', 'jwt'])
    if jwks_uri:
        jwt['jwk-set-uri'] = str(jwks_uri)
    else:
        # remove if present and empty
        jwt.pop('jwk-set-uri', None)

    # replace first document and write all docs back
    docs[0] = data

    try:
        with open(path, 'w', encoding='utf-8') as f:
            yaml.safe_dump_all(docs, f, default_flow_style=False, sort_keys=False)
        print(f"Patched {path} (use-keycloak={use_keycloak}, jwks_uri={'set' if jwks_uri else 'empty'})")
    except Exception as e:
        print(f"Failed to write {path}: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
