#!/usr/bin/env bash

# Detect the active Docker socket from docker context (robust, works everywhere)
SOCKET=$(docker context ls --format '{{if .Current}}{{.DockerEndpoint}}{{end}}' | grep unix | sed 's|unix://||')

echo "Detected Docker socket: $SOCKET"

if [ -z "$SOCKET" ]; then
  echo "Could not determine Docker socket path!"
  exit 1
fi

# Replace all docker.sock mounts in docker-compose.yaml
sed -i "s|/var/run/docker.sock:/var/run/docker.sock|$SOCKET:/var/run/docker.sock|g" docker-compose.yaml