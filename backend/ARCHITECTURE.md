# Backend Architecture - Feature-Based Organization

## Overview

This backend follows a **Domain-Driven Design (DDD)** approach with **feature-based organization** within each domain. This structure promotes high cohesion, low coupling, and better maintainability.

## Directory Structure

```
com.couchbase.admin/
├── dashboard/           # Dashboard metrics and health monitoring
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
├── buckets/            # Couchbase bucket management
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
├── connections/        # Connection management (Couchbase, FHIR, etc.)
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
├── audit/             # Audit logging and compliance
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
├── users/             # User management and authentication
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
└── health/            # Health checks and monitoring
    ├── controller/
    ├── service/
    ├── model/
    └── repository/

com.couchbase.fhir/
├── resources/         # FHIR resource CRUD operations
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
├── search/           # FHIR search functionality
│   ├── controller/
│   ├── service/
│   ├── model/
│   └── repository/
└── validation/       # FHIR resource validation
    ├── controller/
    ├── service/
    ├── model/
    └── repository/

com.couchbase.common/  # Shared components
├── config/           # Configuration classes
├── exceptions/       # Custom exceptions
└── utils/           # Utility classes
```

## Design Principles

### 1. Feature-Based Organization

- **High Cohesion**: Related code (controller, service, model, repository) for a feature are grouped together
- **Low Coupling**: Features are independent of each other
- **Easy Navigation**: All code related to a specific feature is in one place

### 2. Domain Separation

- **Admin Domain**: Management and monitoring features
- **FHIR Domain**: FHIR-specific functionality
- **Common Domain**: Shared utilities and configurations

### 3. Layer Separation

Each feature contains:

- **Controller**: REST API endpoints
- **Service**: Business logic
- **Model**: Data transfer objects (DTOs)
- **Repository**: Data access layer

## Benefits

### 1. Team Collaboration

- Different teams can work on different features without conflicts
- Clear ownership boundaries
- Easier code reviews

### 2. Scalability

- Easy to add new features
- Can extract features into separate modules later
- Supports microservices migration if needed

### 3. Maintainability

- Clear structure makes onboarding easier
- Reduces merge conflicts
- Easier to refactor individual features

### 4. Testing

- Each feature can be tested independently
- Clear test boundaries
- Easier to mock dependencies

## API Endpoints

### Admin Domain

- `/api/dashboard/*` - Dashboard metrics and health
- `/api/buckets/*` - Bucket management
- `/api/connections/*` - Connection management
- `/api/audit/*` - Audit logging
- `/api/users/*` - User management

### FHIR Domain

- `/fhir/{tenant}/resources/*` - FHIR resource operations
- `/fhir/{tenant}/search/*` - FHIR search operations
- `/fhir/{tenant}/validation/*` - FHIR validation

## Adding New Features

1. **Create Feature Directory**: Create a new directory under the appropriate domain
2. **Add Layers**: Create controller, service, model, and repository directories
3. **Implement Classes**: Add the necessary classes for each layer
4. **Update Documentation**: Update this file with the new feature

## Migration from Old Structure

The old structure had:

```
com.couchbase.admin/
├── controller/        # All controllers mixed together
├── service/          # All services mixed together
├── model/           # All models mixed together
└── health/          # Health indicators
```

The new structure provides better organization and maintainability.

## Best Practices

1. **Keep Features Independent**: Avoid dependencies between features
2. **Use Common Package**: Share only truly common components
3. **Consistent Naming**: Use consistent naming conventions across features
4. **Documentation**: Keep documentation updated with new features
5. **Testing**: Write tests for each feature independently
