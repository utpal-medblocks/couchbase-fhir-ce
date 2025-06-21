import React, { useState, useEffect } from "react";
import {
  Box,
  Typography,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Alert,
  LinearProgress,
} from "@mui/material";
import {
  BsActivity,
  BsClock,
  BsSpeedometer2,
  BsCheckCircle,
  BsExclamationTriangle,
  BsCpu,
  BsMemory,
  BsHdd,
  BsGear,
} from "react-icons/bs";

interface FhirMetrics {
  // FHIR Server Health
  server: {
    status: "UP" | "DOWN" | "DEGRADED";
    uptime: string;
    cpuUsage: number; // percentage
    memoryUsage: number; // percentage
    diskUsage: number; // percentage
    jvmThreads: number;
  };

  // FHIR Version Info
  version: {
    fhirVersion: string;
    serverVersion: string;
    buildNumber: string;
  };

  // FHIR Resources Operations (simplified to 3 main operations)
  operations: {
    read: { count: number; avgLatency: number; successRate: number };
    create: { count: number; avgLatency: number; successRate: number };
    search: { count: number; avgLatency: number; successRate: number };
  };

  // Overall Performance
  overall: {
    totalOperations: number;
    currentOpsPerSec: number;
    avgOpsPerSec: number;
  };
}

const DashboardFhirServer: React.FC = () => {
  const [metrics, setMetrics] = useState<FhirMetrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchMetrics = async () => {
      try {
        setLoading(true);
        // TODO: Replace with actual API call
        // const response = await fetch('/api/dashboard/fhir-metrics');
        // const data = await response.json();

        // Mock data for now
        const mockData: FhirMetrics = {
          server: {
            status: "UP",
            uptime: "2d 14h 23m",
            cpuUsage: 24.5,
            memoryUsage: 68.2,
            diskUsage: 45.8,
            jvmThreads: 42,
          },

          version: {
            fhirVersion: "R4",
            serverVersion: "1.0.0-SNAPSHOT",
            buildNumber: "build-2025.06.19-1547",
          },

          operations: {
            read: { count: 756, avgLatency: 95, successRate: 99.2 },
            create: { count: 234, avgLatency: 185, successRate: 98.5 },
            search: { count: 289, avgLatency: 145, successRate: 96.8 },
          },

          overall: {
            totalOperations: 1279,
            currentOpsPerSec: 2.8,
            avgOpsPerSec: 2.3,
          },
        };

        setMetrics(mockData);
        setError(null);
      } catch (err) {
        setError("Failed to fetch FHIR metrics");
        console.error("Error fetching FHIR metrics:", err);
      } finally {
        setLoading(false);
      }
    };

    fetchMetrics();

    // Refresh every 30 seconds
    const interval = setInterval(fetchMetrics, 30000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return (
      <Box
        sx={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: 200,
        }}
      >
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return (
      <Alert severity="error" sx={{ mb: 2 }}>
        {error}
      </Alert>
    );
  }

  if (!metrics) {
    return <Alert severity="info">No FHIR metrics available</Alert>;
  }

  const getUsageColor = (usage: number) => {
    if (usage < 50) return "success";
    if (usage < 80) return "warning";
    return "error";
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "UP":
        return "success";
      case "DEGRADED":
        return "warning";
      case "DOWN":
        return "error";
      default:
        return "default";
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Version Info - Full Width */}
      <Card sx={{ paddingBottom: 0 }}>
        <CardContent sx={{ p: 0 }}>
          <Box
            sx={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
            }}
          >
            {/* <Box sx={{ flex: 1, textAlign: "center" }}>
              <Typography variant="body2" color="text.secondary">
                FHIR Version
              </Typography>
              <Typography variant="h6" fontWeight="bold">
                {metrics.version.fhirVersion}
              </Typography>
            </Box> */}
            <Box sx={{ flex: 1, textAlign: "center" }}>
              <Typography variant="body2" color="text.secondary">
                Server Version
              </Typography>
              <Typography variant="body1">
                {metrics.version.serverVersion}
              </Typography>
            </Box>
            <Box sx={{ flex: 1, textAlign: "center" }}>
              <Typography variant="body2" color="text.secondary">
                Build
              </Typography>
              <Typography variant="body1">
                {metrics.version.buildNumber}
              </Typography>
            </Box>
          </Box>
        </CardContent>
      </Card>

      {/* Server Status and System Resources - Full Width */}
      <Card sx={{ paddingBottom: 0 }}>
        <CardContent sx={{ p: 0 }}>
          <Box sx={{ display: "flex", gap: 2 }}>
            {/* Server Status - 40% width */}
            <Box sx={{ width: "40%" }}>
              <Typography variant="subtitle2" gutterBottom>
                Server Status
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
                <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                  {metrics.server.status === "UP" ? (
                    <BsCheckCircle
                      style={{ color: "#2e7d32", fontSize: "16px" }}
                    />
                  ) : (
                    <BsExclamationTriangle
                      style={{ color: "#d32f2f", fontSize: "16px" }}
                    />
                  )}
                  <Typography variant="body2">
                    Status: {metrics.server.status}
                  </Typography>
                </Box>
                <Typography variant="body2">
                  Uptime: {metrics.server.uptime}
                </Typography>
                <Typography variant="body2">
                  JVM Threads: {metrics.server.jvmThreads}
                </Typography>
                <Typography variant="body2">
                  Current Ops/Sec: {metrics.overall.currentOpsPerSec.toFixed(1)}
                </Typography>
                <Typography variant="body2">
                  Total Ops: {metrics.overall.totalOperations.toLocaleString()}
                </Typography>
              </Box>
            </Box>

            {/* System Resources - 60% width */}
            <Box sx={{ width: "60%" }}>
              <Typography variant="subtitle2" gutterBottom>
                System Resources
              </Typography>
              <Box sx={{ display: "flex", gap: 2 }}>
                {/* CPU Gauge - 33% */}
                <Box sx={{ width: "33%" }}>
                  <Box
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: 1,
                      mb: 1,
                    }}
                  >
                    <BsCpu style={{ fontSize: "16px", color: "#1976d2" }} />
                    <Typography variant="body2">CPU</Typography>
                  </Box>
                  <Box sx={{ textAlign: "center", mb: 1 }}>
                    <Typography
                      variant="h5"
                      color={getUsageColor(metrics.server.cpuUsage) + ".main"}
                    >
                      {metrics.server.cpuUsage.toFixed(1)}%
                    </Typography>
                  </Box>
                  <LinearProgress
                    variant="determinate"
                    value={metrics.server.cpuUsage}
                    color={getUsageColor(metrics.server.cpuUsage) as any}
                    sx={{ height: 8, borderRadius: 4 }}
                  />
                </Box>

                {/* Memory Gauge - 33% */}
                <Box sx={{ width: "33%" }}>
                  <Box
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: 1,
                      mb: 1,
                    }}
                  >
                    <BsMemory style={{ fontSize: "16px", color: "#2e7d32" }} />
                    <Typography variant="body2">Memory</Typography>
                  </Box>
                  <Box sx={{ textAlign: "center", mb: 1 }}>
                    <Typography
                      variant="h5"
                      color={
                        getUsageColor(metrics.server.memoryUsage) + ".main"
                      }
                    >
                      {metrics.server.memoryUsage.toFixed(1)}%
                    </Typography>
                  </Box>
                  <LinearProgress
                    variant="determinate"
                    value={metrics.server.memoryUsage}
                    color={getUsageColor(metrics.server.memoryUsage) as any}
                    sx={{ height: 8, borderRadius: 4 }}
                  />
                </Box>

                {/* Disk Gauge - 33% */}
                <Box sx={{ width: "33%" }}>
                  <Box
                    sx={{
                      display: "flex",
                      alignItems: "center",
                      gap: 1,
                      mb: 1,
                    }}
                  >
                    <BsHdd style={{ fontSize: "16px", color: "#ed6c02" }} />
                    <Typography variant="body2">Disk</Typography>
                  </Box>
                  <Box sx={{ textAlign: "center", mb: 1 }}>
                    <Typography
                      variant="h5"
                      color={getUsageColor(metrics.server.diskUsage) + ".main"}
                    >
                      {metrics.server.diskUsage.toFixed(1)}%
                    </Typography>
                  </Box>
                  <LinearProgress
                    variant="determinate"
                    value={metrics.server.diskUsage}
                    color={getUsageColor(metrics.server.diskUsage) as any}
                    sx={{ height: 8, borderRadius: 4 }}
                  />
                </Box>
              </Box>
            </Box>
          </Box>
        </CardContent>
      </Card>

      {/* FHIR Operations - Full Width */}
      <Card>
        <CardContent sx={{ p: 2 }}>
          <Typography variant="subtitle2" gutterBottom>
            FHIR Operations
          </Typography>
          <Box sx={{ display: "flex", gap: 2 }}>
            {/* Read Operations - 33% */}
            <Box sx={{ width: "33%" }}>
              <Card variant="outlined">
                <CardContent sx={{ p: 2, textAlign: "center" }}>
                  <Chip
                    label="READ"
                    size="small"
                    color="success"
                    sx={{ mb: 1 }}
                  />
                  <Typography variant="h4" color="primary.main" gutterBottom>
                    {metrics.operations.read.count.toLocaleString()}
                  </Typography>
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    gutterBottom
                  >
                    operations
                  </Typography>
                  <Box
                    sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}
                  >
                    <Typography variant="body2">
                      Ops/Sec:{" "}
                      {(metrics.operations.read.count / 3600).toFixed(1)}
                    </Typography>
                    <Typography variant="body2">
                      Avg Latency: {metrics.operations.read.avgLatency}ms
                    </Typography>
                    <Typography
                      variant="body2"
                      color={
                        metrics.operations.read.successRate > 98
                          ? "success.main"
                          : metrics.operations.read.successRate > 95
                          ? "warning.main"
                          : "error.main"
                      }
                    >
                      Success: {metrics.operations.read.successRate.toFixed(1)}%
                    </Typography>
                  </Box>
                </CardContent>
              </Card>
            </Box>

            {/* Create Operations - 33% */}
            <Box sx={{ width: "33%" }}>
              <Card variant="outlined">
                <CardContent sx={{ p: 2, textAlign: "center" }}>
                  <Chip
                    label="CREATE"
                    size="small"
                    color="primary"
                    sx={{ mb: 1 }}
                  />
                  <Typography variant="h4" color="primary.main" gutterBottom>
                    {metrics.operations.create.count.toLocaleString()}
                  </Typography>
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    gutterBottom
                  >
                    operations
                  </Typography>
                  <Box
                    sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}
                  >
                    <Typography variant="body2">
                      Ops/Sec:{" "}
                      {(metrics.operations.create.count / 3600).toFixed(1)}
                    </Typography>
                    <Typography variant="body2">
                      Avg Latency: {metrics.operations.create.avgLatency}ms
                    </Typography>
                    <Typography
                      variant="body2"
                      color={
                        metrics.operations.create.successRate > 98
                          ? "success.main"
                          : metrics.operations.create.successRate > 95
                          ? "warning.main"
                          : "error.main"
                      }
                    >
                      Success:{" "}
                      {metrics.operations.create.successRate.toFixed(1)}%
                    </Typography>
                  </Box>
                </CardContent>
              </Card>
            </Box>

            {/* Search Operations - 33% */}
            <Box sx={{ width: "33%" }}>
              <Card variant="outlined">
                <CardContent sx={{ p: 2, textAlign: "center" }}>
                  <Chip
                    label="SEARCH"
                    size="small"
                    color="warning"
                    sx={{ mb: 1 }}
                  />
                  <Typography variant="h4" color="primary.main" gutterBottom>
                    {metrics.operations.search.count.toLocaleString()}
                  </Typography>
                  <Typography
                    variant="body2"
                    color="text.secondary"
                    gutterBottom
                  >
                    operations
                  </Typography>
                  <Box
                    sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}
                  >
                    <Typography variant="body2">
                      Ops/Sec:{" "}
                      {(metrics.operations.search.count / 3600).toFixed(1)}
                    </Typography>
                    <Typography variant="body2">
                      Avg Latency: {metrics.operations.search.avgLatency}ms
                    </Typography>
                    <Typography
                      variant="body2"
                      color={
                        metrics.operations.search.successRate > 98
                          ? "success.main"
                          : metrics.operations.search.successRate > 95
                          ? "warning.main"
                          : "error.main"
                      }
                    >
                      Success:{" "}
                      {metrics.operations.search.successRate.toFixed(1)}%
                    </Typography>
                  </Box>
                </CardContent>
              </Card>
            </Box>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
};

export default DashboardFhirServer;
