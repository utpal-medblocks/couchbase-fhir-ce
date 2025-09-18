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

## Log Rotation & S3 Uploads

Log Rotation is enabled by default. ~~Rotated logs can be configured to be uploaded to an S3 Bucket for complying with Audit requiremeents.~~ **Note: S3 upload functionality is currently disabled for the Beta release.** To learn more read [LOG_ROTATION_AND_S3_UPLOAD.md](./LOG_ROTATION_AND_S3_UPLOAD.md)
