# Couchbase FHIR CE - Project Development Guide

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture Overview](#architecture-overview)
3. [Project Structure](#project-structure)
4. [Development Setup](#development-setup)
5. [Team Responsibilities](#team-responsibilities)
6. [Development Guidelines](#development-guidelines)
7. [API Documentation](#api-documentation)
8. [Deployment](#deployment)
9. [Best Practices](#best-practices)
10. [Troubleshooting](#troubleshooting)

## Project Overview

**Couchbase FHIR CE** is an open-source FHIR server and admin UI built with Couchbase, Spring Boot, and React. The project consists of two main components:

- **Backend**: Spring Boot application with HAPI FHIR for FHIR API implementation
- **Frontend**: React-based admin UI for managing Couchbase and FHIR resources

### Key Technologies

- **Backend**: Java 17, Spring Boot 3.5.0, HAPI FHIR 8.2.0, Couchbase
- **Frontend**: React 19, TypeScript, Material-UI, Vite, Zustand
- **Database**: Couchbase Server/Capella

## Architecture Overview

The project follows a **monorepo structure** with clear separation between frontend and backend:

```
couchbase-fhir-ce/
├── backend/          # Spring Boot FHIR Server
├── frontend/         # React Admin UI
├── config.yaml       # Application configuration
└── README.md         # Project overview
```

### Backend Architecture

The backend follows **Domain-Driven Design (DDD)** with feature-based organization:

- **Admin Domain**: Management and monitoring features
- **FHIR Domain**: FHIR-specific functionality
- **Common Domain**: Shared utilities and configurations

### Frontend Architecture

The frontend follows a **component-based architecture** with:

- **Pages**: Main application views
- **Components**: Reusable UI components
- **Services**: API communication layer
- **Store**: State management with Zustand
- **Hooks**: Custom React hooks

## Project Structure

### Root Level

```
couchbase-fhir-ce/
├── .git/                    # Git repository
├── .gitignore              # Git ignore rules
├── .vscode/                # VS Code settings
├── config.yaml             # Application configuration
├── LICENSE                 # Project license
├── README.md               # Project overview
└── PROJECT_GUIDE.md        # This document
```

### Backend Structure (`backend/`)

```
backend/
├── src/
│   ├── main/
│   │   ├── java/com/couchbase/
│   │   │   ├── CouchbaseFhirApplication.java    # Main application class
│   │   │   ├── admin/                           # Admin domain
│   │   │   │   ├── audit/                       # Audit logging
│   │   │   │   ├── buckets/                     # Bucket management
│   │   │   │   ├── connections/                 # Connection management
│   │   │   │   ├── dashboard/                   # Dashboard metrics
│   │   │   │   ├── fhirBucket/                  # FHIR bucket config
│   │   │   │   └── users/                       # User management
│   │   │   ├── fhir/                            # FHIR domain
│   │   │   │   ├── resources/                   # FHIR resource CRUD
│   │   │   │   ├── search/                      # FHIR search
│   │   │   │   └── validation/                  # FHIR validation
│   │   │   └── common/                          # Shared components
│   │   │       ├── config/                      # Configuration
│   │   │       └── exceptions/                  # Custom exceptions
│   │   └── resources/
│   │       ├── application.properties           # Spring Boot config
│   │       ├── application.yml                  # Application config
│   │       └── fhir.yml                         # FHIR configuration
│   └── test/                                    # Test files
├── pom.xml                                      # Maven configuration
├── ARCHITECTURE.md                              # Backend architecture docs
└── compose.yaml                                 # Docker Compose setup
```

### Frontend Structure (`frontend/`)

```
frontend/
├── src/
│   ├── App.tsx                                 # Main application component
│   ├── main.tsx                                # Application entry point
│   ├── index.css                               # Global styles
│   ├── App.css                                 # App-specific styles
│   ├── vite-env.d.ts                           # Vite type definitions
│   ├── assets/                                 # Static assets
│   │   └── icons/                              # Application icons
│   ├── components/                             # Reusable components
│   │   ├── BucketPageExample.tsx
│   │   ├── ChipsArray.tsx
│   │   └── CustomTextField.tsx
│   ├── contexts/                               # React contexts
│   │   └── ThemeContext.tsx                    # Theme management
│   ├── hooks/                                  # Custom React hooks
│   │   ├── useFhirMetrics.ts
│   │   └── useTheme.ts
│   ├── pages/                                  # Application pages
│   │   ├── AuditLogs/                          # Audit logs page
│   │   ├── Buckets/                            # Bucket management
│   │   ├── Connections/                        # Connection management
│   │   ├── Dashboard/                          # Main dashboard
│   │   ├── FHIRResources/                      # FHIR resources
│   │   ├── Layout/                             # Layout components
│   │   ├── SystemLogs/                         # System logs
│   │   ├── Users/                              # User management
│   │   └── Workbench/                          # FHIR workbench
│   ├── routes/                                 # Routing configuration
│   │   └── AppRoutes.tsx
│   ├── services/                               # API services
│   │   ├── connectionService.ts
│   │   └── fhirMetricsService.ts
│   ├── store/                                  # State management
│   │   ├── bucketStore.ts
│   │   ├── configStore.ts
│   │   └── connectionStore.ts
│   ├── styles/                                 # Styling utilities
│   ├── theme/                                  # Theme configuration
│   ├── types/                                  # TypeScript types
│   │   └── config.ts
│   └── utilities/                              # Utility functions
│       └── thresholds.tsx
├── public/                                     # Public assets
├── package.json                                # Node.js dependencies
├── package-lock.json                           # Dependency lock file
├── eslint.config.js                            # ESLint configuration
├── index.html                                  # HTML template
└── vite.config.ts                              # Vite configuration
```

## Development Setup

### Prerequisites

- **Java 17** (for backend development)
- **Node.js 18+** (for frontend development)
- **Couchbase Server 7.0+** or **Couchbase Capella** account
- **Git** for version control

### Backend Setup

1. **Navigate to backend directory**:

   ```bash
   cd couchbase-fhir-ce/backend
   ```

2. **Install dependencies**:

   ```bash
   mvn clean install
   ```

3. **Configure Couchbase connection** in `src/main/resources/application.yml`

4. **Run the application**:
   ```bash
   mvn spring-boot:run
   ```

### Frontend Setup

1. **Navigate to frontend directory**:

   ```bash
   cd couchbase-fhir-ce/frontend
   ```

2. **Install dependencies**:

   ```bash
   npm install
   ```

3. **Start development server**:
   ```bash
   npm run dev
   ```

### Configuration

The application uses `config.yaml` at the root level for configuration:

```yaml
connection:
  connectionString: "couchbase://localhost"
  username: "Administrator"
  password: "password"
  serverType: "Server" # "Server" or "Capella"
  sslEnabled: false

fhir:
  profile: "US Core"
  endpoint: "/fhir/demo"
  version: "V4"

app:
  autoConnect: true
  showConnectionDialog: false
```

## Team Responsibilities

### Backend Team

**Primary Responsibilities:**

- FHIR API implementation and compliance
- Couchbase data access layer
- Authentication and authorization
- System monitoring and metrics
- Database schema design
- API documentation

**Key Areas:**

- `backend/src/main/java/com/couchbase/fhir/` - FHIR domain
- `backend/src/main/java/com/couchbase/admin/` - Admin APIs
- `backend/src/main/java/com/couchbase/common/` - Shared utilities

### Frontend Team

**Primary Responsibilities:**

- Admin UI development
- User experience and interface design
- State management
- API integration
- Responsive design
- Performance optimization

**Key Areas:**

- `frontend/src/pages/` - Application pages
- `frontend/src/components/` - Reusable components
- `frontend/src/services/` - API communication
- `frontend/src/store/` - State management

### Shared Responsibilities

- Code quality and testing
- Documentation
- Performance monitoring
- Security best practices

## Development Guidelines

### Code Style and Standards

#### Backend (Java)

- Follow **Google Java Style Guide**
- Use **Lombok** for boilerplate code reduction
- Implement **proper exception handling**
- Write **unit tests** for all business logic
- Use **Spring Boot best practices**

#### Frontend (TypeScript/React)

- Follow **Airbnb JavaScript Style Guide**
- Use **TypeScript** for type safety
- Implement **functional components** with hooks
- Use **Material-UI** for consistent design
- Write **unit tests** for components

### Git Workflow

1. **Feature branches**: Create feature branches from `main`
2. **Commit messages**: Use conventional commit format
3. **Pull requests**: Required for all changes
4. **Code review**: Mandatory before merging

### Testing Strategy

- **Backend**: Unit tests with JUnit, integration tests
- **Frontend**: Unit tests with Jest, component tests
- **E2E**: End-to-end testing for critical flows

## API Documentation

### Backend APIs

#### Admin APIs

- `GET /api/dashboard/*` - Dashboard metrics and health
- `GET /api/buckets/*` - Bucket management
- `GET /api/connections/*` - Connection management
- `GET /api/audit/*` - Audit logging
- `GET /api/users/*` - User management

#### FHIR APIs

- `GET /fhir/{tenant}/resources/*` - FHIR resource operations
- `GET /fhir/{tenant}/search/*` - FHIR search operations
- `GET /fhir/{tenant}/validation/*` - FHIR validation

### Frontend Services

- `connectionService.ts` - Connection management
- `fhirMetricsService.ts` - FHIR metrics and monitoring

## Deployment

### Docker Deployment

The project includes Docker Compose configuration for easy deployment:

```bash
# Build and run with Docker Compose
docker-compose up --build
```

### Production Considerations

- **Environment variables** for sensitive configuration
- **Health checks** for monitoring
- **Logging** configuration
- **Security** hardening
- **Performance** optimization

## Best Practices

### Backend Development

1. **Feature-based organization**: Keep related code together
2. **Dependency injection**: Use Spring's DI container
3. **Exception handling**: Implement proper error handling
4. **Validation**: Validate all inputs
5. **Documentation**: Document all public APIs

### Frontend Development

1. **Component composition**: Build reusable components
2. **State management**: Use Zustand for global state
3. **Type safety**: Leverage TypeScript features
4. **Performance**: Implement lazy loading and optimization
5. **Accessibility**: Follow WCAG guidelines

### General Practices

1. **Code review**: Review all changes
2. **Testing**: Write tests for new features
3. **Documentation**: Keep documentation updated
4. **Security**: Follow security best practices
5. **Performance**: Monitor and optimize performance

## Troubleshooting

### Common Issues

#### Backend Issues

- **Connection errors**: Check Couchbase connection settings
- **Port conflicts**: Ensure port 8080 is available
- **Memory issues**: Increase JVM heap size if needed

#### Frontend Issues

- **Build errors**: Clear node_modules and reinstall
- **API errors**: Check backend connectivity
- **Styling issues**: Verify Material-UI theme configuration

### Getting Help

1. Check the **ARCHITECTURE.md** file for backend details
2. Review **package.json** for dependency information
3. Check **application logs** for error details
4. Consult **team leads** for complex issues

---

## Quick Reference

### Development Commands

```bash
# Backend
cd backend && ./mvnw spring-boot:run -Dspring.profiles.active=development

# Frontend
cd frontend && npm run dev

# Build both
cd backend && mvn clean install
cd frontend && npm run build
```

### Key Files

- `config.yaml` - Application configuration
- `backend/pom.xml` - Backend dependencies
- `frontend/package.json` - Frontend dependencies
- `backend/ARCHITECTURE.md` - Backend architecture details

### Important URLs

- **Backend**: http://localhost:8080
- **Frontend**: http://localhost:5173
- **API Docs**: http://localhost:8080/swagger-ui.html

---

_This document should be updated as the project evolves. Last updated: [Current Date]_
