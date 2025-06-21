rename # Dashboard Metrics API

This document describes the available endpoints for retrieving application metrics for the dashboard.

## Base URL

```
http://localhost:8080/api/dashboard
```

## Endpoints

### 1. Get All Dashboard Metrics

**GET** `/metrics`

Returns comprehensive metrics including health, system, JVM, and application metrics.

**Response:**

```json
{
  "health": {
    "status": "UP",
    "details": {
      "couchbase": {
        "status": "UP",
        "details": {
          "database": "couchbase",
          "status": "available"
        }
      }
    }
  },
  "systemMetrics": {
    "cpu.usage.percent": 25.5,
    "load.average.1m": 2.1,
    "cpu.count": 8,
    "disk.usage.percent": 45.2
  },
  "jvmMetrics": {
    "memory.usage.percent": 65.3,
    "memory.used.bytes": 536870912,
    "memory.max.bytes": 1073741824,
    "threads.live": 42
  },
  "applicationMetrics": {
    "http.requests.total": 1250,
    "http.connections.active": 5
  },
  "uptime": "2d 5h 30m 15s",
  "timestamp": 1640995200000
}
```

### 2. Get Health Status Only

**GET** `/health`

Returns simplified health status.

**Response:**

```json
{
  "status": "UP",
  "details": {
    "couchbase": {
      "status": "UP"
    }
  },
  "timestamp": 1640995200000
}
```

### 3. Get System Metrics

**GET** `/system`

Returns system and JVM metrics.

**Response:**

```json
{
  "system": {
    "cpu.usage.percent": 25.5,
    "load.average.1m": 2.1,
    "cpu.count": 8,
    "disk.usage.percent": 45.2
  },
  "jvm": {
    "memory.usage.percent": 65.3,
    "threads.live": 42
  },
  "uptime": "2d 5h 30m 15s",
  "timestamp": 1640995200000
}
```

### 4. Get Application Metrics

**GET** `/application`

Returns application-specific metrics and info.

**Response:**

```json
{
  "info": {
    "app": {
      "name": "Couchbase FHIR CE Backend",
      "version": "0.0.1-SNAPSHOT"
    }
  },
  "metrics": {
    "http.requests.total": 1250,
    "fhir.operations": 850
  },
  "timestamp": 1640995200000
}
```

### 5. Record FHIR Operation (For Internal Use)

**POST** `/fhir/operation`

Records FHIR operation metrics.

**Parameters:**

- `operation` (required): The FHIR operation (READ, CREATE, SEARCH, etc.)
- `resourceType` (required): The FHIR resource type (Patient, Observation, etc.)
- `duration` (optional): Operation duration in milliseconds

## Built-in Actuator Endpoints

The following Spring Boot Actuator endpoints are also available:

- **GET** `/actuator/health` - Health check endpoint
- **GET** `/actuator/info` - Application information
- **GET** `/actuator/metrics` - Detailed metrics (with metric names)
- **GET** `/actuator/metrics/{metricName}` - Specific metric details
- **GET** `/actuator/prometheus` - Prometheus formatted metrics

## Frontend Integration

### JavaScript Example

```javascript
// Fetch dashboard metrics
async function fetchDashboardMetrics() {
  try {
    const response = await fetch("/api/dashboard/metrics");
    const metrics = await response.json();

    // Update your dashboard UI
    updateCPUUsage(metrics.systemMetrics["cpu.usage.percent"]);
    updateMemoryUsage(metrics.jvmMetrics["memory.usage.percent"]);
    updateHealthStatus(metrics.health.status);
    updateUptime(metrics.uptime);
  } catch (error) {
    console.error("Failed to fetch metrics:", error);
  }
}

// Fetch every 30 seconds
setInterval(fetchDashboardMetrics, 30000);
```

### React Example

```jsx
import { useState, useEffect } from "react";

function DashboardMetrics() {
  const [metrics, setMetrics] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchMetrics = async () => {
      try {
        const response = await fetch("/api/dashboard/metrics");
        const data = await response.json();
        setMetrics(data);
      } catch (error) {
        console.error("Error fetching metrics:", error);
      } finally {
        setLoading(false);
      }
    };

    fetchMetrics();
    const interval = setInterval(fetchMetrics, 30000);

    return () => clearInterval(interval);
  }, []);

  if (loading) return <div>Loading metrics...</div>;

  return (
    <div className="dashboard-metrics">
      <div className="metric-card">
        <h3>System Health</h3>
        <p>Status: {metrics?.health?.status}</p>
        <p>Uptime: {metrics?.uptime}</p>
      </div>

      <div className="metric-card">
        <h3>Performance</h3>
        <p>CPU: {metrics?.systemMetrics?.["cpu.usage.percent"]?.toFixed(1)}%</p>
        <p>
          Memory: {metrics?.jvmMetrics?.["memory.usage.percent"]?.toFixed(1)}%
        </p>
      </div>

      <div className="metric-card">
        <h3>Traffic</h3>
        <p>
          Total Requests: {metrics?.applicationMetrics?.["http.requests.total"]}
        </p>
      </div>
    </div>
  );
}
```

## Caching

The `/metrics` endpoint is cached for 30 seconds to reduce server load. For real-time data, use specific endpoints like `/health` or `/system`.

## Error Handling

All endpoints return graceful error responses with partial data when possible. Monitor the application logs for detailed error information.
