# Docker Deployment Guide

This guide describes how to run the project using Docker and Docker Compose.

## 1. Prepare Configuration

The backend service requires a `config.yaml` file, which is mounted into the container. To create it:

```bash
cp config.yaml.template config.yaml
```
Edit `config.yaml` as needed for your environment.

## 2. Set the Docker Socket Mount

The backend service (`fhir-server`) needs access to the Docker socket. The correct socket path may vary depending on your Docker context. Use the provided script to automatically detect and update the mount:

```bash
./init-docker-compose.sh
```
This will update `docker-compose.yaml` to mount the correct Docker socket into the `fhir-server` container.

## 3. Build and Run the Containers

Build all services:

```bash
docker compose build
```

Start all services in the background:

```bash
docker compose up -d
```

To view logs for all services:

```bash
docker compose logs -f
```

To stop all services:

```bash
docker compose down
```

To stop and remove all services, networks, and named volumes (including data):

```bash
docker compose down -v
```

## 4. Additional Commands

- To rebuild a specific service:
  ```bash
  docker compose build <service-name>
  ```
- To restart a specific service:
  ```bash
  docker compose restart <service-name>
  ```

---

For more details, see the main `README.md`.
