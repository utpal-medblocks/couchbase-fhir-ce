# Couchbase FHIR CE

Open source FHIR server and admin UI with Couchbase and Spring Boot with HAPI

## Overview

Couchbase FHIR CE is a comprehensive FHIR (Fast Healthcare Interoperability Resources) server implementation built on Couchbase with a modern React-based admin interface. This project provides a complete solution for healthcare data management and FHIR compliance.

## Project Structure

```
couchbase-fhir-ce/
├── backend/          # Spring Boot FHIR Server
├── frontend/         # React Admin UI
├── config.yaml       # Application configuration
├── README.md         # This file
└── PROJECT_GUIDE.md  # Comprehensive development guide
```

## Quick Start

### Prerequisites

- Java 17
- Node.js 18+
- Couchbase Server 7.0+ or Couchbase Capella account

### Backend Setup

```bash
cd backend
mvn spring-boot:run
```

### Frontend Setup

```bash
cd frontend
npm install
npm run dev
```

## Documentation

For detailed information about:

- **Project Architecture**: See `PROJECT_GUIDE.md`
- **Backend Architecture**: See `backend/ARCHITECTURE.md`
- **Development Guidelines**: See `PROJECT_GUIDE.md`
- **Team Responsibilities**: See `PROJECT_GUIDE.md`

## Key Features

- **FHIR R4 Compliance**: Full FHIR R4 resource support
- **Couchbase Integration**: Native Couchbase data storage
- **Admin UI**: Modern React-based management interface
- **Multi-tenant Support**: Tenant-based FHIR resource isolation
- **Audit Logging**: Comprehensive audit trail
- **Health Monitoring**: System health and metrics dashboard

## License

This project is licensed under the terms specified in the LICENSE file.

---

**For detailed development information, please refer to [PROJECT_GUIDE.md](./PROJECT_GUIDE.md)**

## Docker Deployment

See [Docker-Deployment.md](./Docker-Deployment.md) for instructions on running this project with Docker and Docker Compose.

### Compose Files Explained

There are TWO compose files with different purposes:

| File                      | Purpose                                                                 | Builds from source?       | Used by installer?                                 |
| ------------------------- | ----------------------------------------------------------------------- | ------------------------- | -------------------------------------------------- |
| `docker-compose.yaml`     | Local development (iterate on code, build images locally)               | Yes (`build:` directives) | No                                                 |
| `docker-compose.user.yml` | Distribution template consumed by `install.sh` (pulls pre-built images) | No (uses `image:` tags)   | Yes (downloaded and saved as `docker-compose.yml`) |

When a user runs the one‑liner:

```
curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml
```

The script fetches `docker-compose.user.yml` from GitHub and writes it locally as `docker-compose.yml`. Integrity is verified using SHA256 hashes embedded in `install.sh`.

#### Keeping Them in Sync

If you make a functional change (environment variables, volumes, ports) to `docker-compose.yaml` that should also affect user deployments, manually port the relevant parts to `docker-compose.user.yml` and then:

1. Run the checksum helper to refresh hashes:
   ```bash
   ./scripts/update-checksums.sh
   ```
2. Commit the updated `docker-compose.user.yml` and `install.sh`.

If you only change build-time details (e.g., adding a `build` arg), you typically do NOT need to update the user template.

#### Customizing Runtime User

`docker-compose.user.yml` allows overriding the container user via environment variables before running the installer:

```bash
export FHIR_RUN_UID=$(id -u)
export FHIR_RUN_GID=$(id -g)
```

This helps avoid permission issues for bind-mounted log directories.

### Updating Installer Hashes

The script `scripts/update-checksums.sh` recalculates SHA256 hashes for:

- `docker-compose.user.yml` (distributed as `docker-compose.yml`)
- `haproxy.cfg`

It then updates both checksum blocks in `install.sh` (Linux `sha256sum` and macOS `shasum` fallback). Use `DRY_RUN=1` to preview or `SKIP_HAPROXY=1` if only the compose file changed.

Example:

```bash
./scripts/update-checksums.sh         # update both
SKIP_HAPROXY=1 ./scripts/update-checksums.sh
DRY_RUN=1 ./scripts/update-checksums.sh
```

If integrity verification fails for users, ensure:

- They are retrieving the latest `install.sh`.
- The hashes in `install.sh` match the live raw GitHub contents of the downloaded files.

## Log Rotation & S3 Uploads

Log Rotation is enabled by default. ~~Rotated logs can be configured to be uploaded to an S3 Bucket for complying with Audit requiremeents.~~ **Note: S3 upload functionality is currently disabled for the Beta release.** To learn more read [LOG_ROTATION_AND_S3_UPLOAD.md](./LOG_ROTATION_AND_S3_UPLOAD.md)
