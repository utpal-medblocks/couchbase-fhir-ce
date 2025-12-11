#!/usr/bin/env python3
import sys
import yaml
import shlex

def q(v):
    if v is None:
        return "''"
    # convert booleans to lower-case strings
    if isinstance(v, bool):
        v = str(v).lower()
    return shlex.quote(str(v))

def main():
    if len(sys.argv) < 2:
        print('Usage: parse_config.py path/to/config.yaml', file=sys.stderr)
        sys.exit(2)
    cfg = yaml.safe_load(open(sys.argv[1]))
    kc = (cfg.get('keycloak') or {})
    print(f"KEYCLOAK_ENABLED={q(kc.get('enabled', False))}")
    print(f"KEYCLOAK_URL={q(kc.get('url',''))}")
    print(f"KEYCLOAK_REALM={q(kc.get('realm',''))}")
    print(f"KEYCLOAK_ADMIN_USERNAME={q(kc.get('adminUsername',''))}")
    print(f"KEYCLOAK_ADMIN_PASSWORD={q(kc.get('adminPassword',''))}")
    print(f"KEYCLOAK_CLIENT_ID={q(kc.get('clientId',''))}")
    print(f"KEYCLOAK_CLIENT_SECRET={q(kc.get('clientSecret',''))}")

if __name__ == '__main__':
    main()
