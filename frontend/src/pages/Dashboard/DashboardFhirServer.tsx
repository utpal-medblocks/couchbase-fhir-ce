import React, { useState, useEffect } from "react";
import {
  Box,
  Typography,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Alert,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  useTheme,
} from "@mui/material";
import { BsCheckCircle, BsExclamationTriangle } from "react-icons/bs";
import { GaugeChart } from "../../components/GuageChart";
import LinearProgressWithLabel from "../../components/LinearProgressWithLabel";
import { tableCellStyle, tableHeaderStyle } from "../../styles/styles";
import { blueGrey } from "@mui/material/colors";

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
      //      console.log("🚀 DashboardFhirServer: Starting fetchMetrics");
      const startTime = performance.now();

      try {
        setLoading(true);
        //        console.log("⏱️ DashboardFhirServer: Set loading state");

        // Call the real backend API with timeout
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000); // 5 second timeout

        // console.log(
        //   "🌐 DashboardFhirServer: Making API call to /api/dashboard/metrics"
        // );
        const apiStartTime = performance.now();

        const response = await fetch("/api/dashboard/metrics", {
          signal: controller.signal,
        });

        const apiEndTime = performance.now();
        // console.log(
        //   `✅ DashboardFhirServer: API call completed in ${(
        //     apiEndTime - apiStartTime
        //   ).toFixed(2)}ms`
        // );

        clearTimeout(timeoutId);

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        //        console.log("📊 DashboardFhirServer: Parsing JSON response");
        const parseStartTime = performance.now();
        const data = await response.json();
        const parseEndTime = performance.now();
        // console.log(
        //   `✅ DashboardFhirServer: JSON parsing completed in ${(
        //     parseEndTime - parseStartTime
        //   ).toFixed(2)}ms`
        // );

        if (data.error) {
          throw new Error(data.error);
        }

        //        console.log("🔄 DashboardFhirServer: Transforming data");
        const transformStartTime = performance.now();

        // Combine FHIR data with real system metrics
        const transformedData: FhirMetrics = {
          server: {
            status:
              data.fhirMetrics?.server?.status || data.health?.status || "DOWN",
            uptime: data.fhirMetrics?.server?.uptime || data.uptime || "0s",
            cpuUsage: data.systemMetrics?.["cpu.usage.percent"] || 0,
            memoryUsage: data.jvmMetrics?.["memory.usage.percent"] || 0,
            diskUsage: data.systemMetrics?.["disk.usage.percent"] || 0,
            jvmThreads: data.jvmMetrics?.["threads.live"] || 0,
          },
          version: {
            fhirVersion: data.fhirMetrics?.version?.fhirVersion || "R4",
            serverVersion:
              data.fhirMetrics?.version?.serverVersion || "Unknown",
            buildNumber: data.fhirMetrics?.version?.buildNumber || "Unknown",
          },
          operations: {
            read: {
              count: data.fhirMetrics?.operations?.read?.count || 0,
              avgLatency: data.fhirMetrics?.operations?.read?.avgLatency || 0,
              successRate: data.fhirMetrics?.operations?.read?.successRate || 0,
            },
            create: {
              count: data.fhirMetrics?.operations?.create?.count || 0,
              avgLatency: data.fhirMetrics?.operations?.create?.avgLatency || 0,
              successRate:
                data.fhirMetrics?.operations?.create?.successRate || 0,
            },
            search: {
              count: data.fhirMetrics?.operations?.search?.count || 0,
              avgLatency: data.fhirMetrics?.operations?.search?.avgLatency || 0,
              successRate:
                data.fhirMetrics?.operations?.search?.successRate || 0,
            },
          },
          overall: {
            totalOperations: data.fhirMetrics?.overall?.totalOperations || 0,
            currentOpsPerSec: data.fhirMetrics?.overall?.currentOpsPerSec || 0,
            avgOpsPerSec: data.fhirMetrics?.overall?.avgOpsPerSec || 0,
          },
        };

        const transformEndTime = performance.now();
        // console.log(
        //   `✅ DashboardFhirServer: Data transformation completed in ${(
        //     transformEndTime - transformStartTime
        //   ).toFixed(2)}ms`
        // );

        //        console.log("💾 DashboardFhirServer: Setting metrics state");
        setMetrics(transformedData);
        setError(null);

        const endTime = performance.now();
        // console.log(
        //   `🎉 DashboardFhirServer: Total fetchMetrics completed in ${(
        //     endTime - startTime
        //   ).toFixed(2)}ms`
        // );
      } catch (err: any) {
        //        console.error("❌ DashboardFhirServer: Error occurred:", err);
        if (err.name === "AbortError") {
          setError("Request timed out - backend may not be responding");
        } else if (err.message.includes("404")) {
          setError("FHIR metrics endpoint not available");
        } else {
          setError("Failed to fetch FHIR metrics");
        }
        //        console.error("Error fetching FHIR metrics:", err);
      } finally {
        //        console.log("🏁 DashboardFhirServer: Setting loading to false");
        setLoading(false);
      }
    };

    // console.log(
    //   "🔄 DashboardFhirServer: useEffect triggered, calling fetchMetrics"
    // );
    fetchMetrics();

    // Refresh every 60 seconds instead of 30 to reduce load
    // TEMPORARILY DISABLED FOR DEVELOPMENT
    // const interval = setInterval(fetchMetrics, 60000);
    // return () => clearInterval(interval);
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

  // Add null check for metrics
  if (!metrics || !metrics.server) {
    return (
      <Box
        sx={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: 200,
        }}
      >
        <Typography variant="body2" color="text.secondary">
          Loading metrics...
        </Typography>
      </Box>
    );
  }

  // Additional safety checks for server metrics
  const safeMetrics = {
    server: {
      status: metrics.server?.status || "DOWN",
      uptime: metrics.server?.uptime || "0s",
      cpuUsage:
        typeof metrics.server?.cpuUsage === "number"
          ? metrics.server.cpuUsage
          : 0,
      memoryUsage:
        typeof metrics.server?.memoryUsage === "number"
          ? metrics.server.memoryUsage
          : 0,
      diskUsage:
        typeof metrics.server?.diskUsage === "number"
          ? metrics.server.diskUsage
          : 0,
      jvmThreads:
        typeof metrics.server?.jvmThreads === "number"
          ? metrics.server.jvmThreads
          : 0,
    },
    version: {
      fhirVersion: metrics.version?.fhirVersion || "Unknown",
      serverVersion: metrics.version?.serverVersion || "Unknown",
      buildNumber: metrics.version?.buildNumber || "Unknown",
    },
    operations: {
      read: {
        count:
          typeof metrics.operations?.read?.count === "number"
            ? metrics.operations.read.count
            : 0,
        avgLatency:
          typeof metrics.operations?.read?.avgLatency === "number"
            ? metrics.operations.read.avgLatency
            : 0,
        successRate:
          typeof metrics.operations?.read?.successRate === "number"
            ? metrics.operations.read.successRate
            : 0,
      },
      create: {
        count:
          typeof metrics.operations?.create?.count === "number"
            ? metrics.operations.create.count
            : 0,
        avgLatency:
          typeof metrics.operations?.create?.avgLatency === "number"
            ? metrics.operations.create.avgLatency
            : 0,
        successRate:
          typeof metrics.operations?.create?.successRate === "number"
            ? metrics.operations.create.successRate
            : 0,
      },
      search: {
        count:
          typeof metrics.operations?.search?.count === "number"
            ? metrics.operations.search.count
            : 0,
        avgLatency:
          typeof metrics.operations?.search?.avgLatency === "number"
            ? metrics.operations.search.avgLatency
            : 0,
        successRate:
          typeof metrics.operations?.search?.successRate === "number"
            ? metrics.operations.search.successRate
            : 0,
      },
    },
    overall: {
      avgOpsPerSec:
        typeof metrics.overall?.avgOpsPerSec === "number"
          ? metrics.overall.avgOpsPerSec
          : 0,
      currentOpsPerSec:
        typeof metrics.overall?.currentOpsPerSec === "number"
          ? metrics.overall.currentOpsPerSec
          : 0,
      totalOperations:
        typeof metrics.overall?.totalOperations === "number"
          ? metrics.overall.totalOperations
          : 0,
    },
  };

  if (!metrics) {
    return <Alert severity="info">No FHIR metrics available</Alert>;
  }

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Version Info - Full Width */}
      {/* <Card sx={{ paddingBottom: 0 }}>
        <CardContent sx={{ p: 0 }}> */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Box sx={{ flex: 1, textAlign: "center" }}>
          <Typography variant="body2" color="text.secondary">
            Server Version
          </Typography>
          <Typography variant="body1">
            {safeMetrics.version.serverVersion}
          </Typography>
        </Box>
        <Box sx={{ flex: 1, textAlign: "center" }}>
          <Typography variant="body2" color="text.secondary">
            Build
          </Typography>
          <Typography variant="body1">
            {safeMetrics.version.buildNumber}
          </Typography>
        </Box>
      </Box>
      {/* </CardContent>
      </Card> */}

      {/* Server Status and System Resources - Full Width */}
      {/* <Card sx={{ paddingBottom: 0 }}>
        <CardContent sx={{ p: 0 }}> */}
      <Box sx={{ display: "flex", gap: 1, p: 1 }}>
        {/* Server Status - 40% width */}
        <Box sx={{ width: "40%" }}>
          <Typography variant="subtitle1" gutterBottom>
            Server Status
          </Typography>
          <TableContainer>
            <Table
              stickyHeader
              size="small"
              sx={{ border: "1px solid divider" }}
            >
              <TableBody>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Status</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {safeMetrics.server.status}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Uptime</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {safeMetrics.server.uptime}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>JVM Threads</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {safeMetrics.server.jvmThreads}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Current Ops/Sec</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {safeMetrics.overall.currentOpsPerSec.toFixed(1)}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Total Ops</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {safeMetrics.overall.totalOperations.toLocaleString()}
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>

          {/* <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
            <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
              {safeMetrics.server.status === "UP" ? (
                <BsCheckCircle style={{ color: "#2e7d32", fontSize: "16px" }} />
              ) : (
                <BsExclamationTriangle
                  style={{ color: "#d32f2f", fontSize: "16px" }}
                />
              )}
              <Typography variant="body2">
                Status: {safeMetrics.server.status}
              </Typography>
            </Box>
            <Typography variant="body2">
              Uptime: {safeMetrics.server.uptime}
            </Typography>
            <Typography variant="body2">
              JVM Threads: {safeMetrics.server.jvmThreads}
            </Typography>
            <Typography variant="body2">
              Current Ops/Sec: {safeMetrics.overall.currentOpsPerSec.toFixed(1)}
            </Typography>
            <Typography variant="body2">
              Total Ops: {safeMetrics.overall.totalOperations.toLocaleString()}
            </Typography>
          </Box>
        </Box> */}
        </Box>
        {/* System Resources - 60% width */}
        <Box sx={{ width: "60%" }}>
          <Typography variant="subtitle1" gutterBottom>
            System Resources
          </Typography>
          <Box sx={{ display: "flex", gap: 2 }}>
            {/* CPU Gauge - 33% */}
            <Box
              sx={{
                width: "33%",
                position: "relative",
              }}
            >
              <GaugeChart name="CPU" value={safeMetrics.server.cpuUsage} />
            </Box>

            {/* Memory Gauge - 33% */}
            <Box sx={{ width: "33%", position: "relative" }}>
              <GaugeChart name="RAM" value={safeMetrics.server.memoryUsage} />
            </Box>

            {/* Disk Gauge - 33% */}
            <Box sx={{ width: "33%", position: "relative" }}>
              <GaugeChart name="Disk" value={safeMetrics.server.diskUsage} />
            </Box>
          </Box>
        </Box>
      </Box>
      {/* </CardContent>
      </Card> */}

      {/* FHIR Operations - Full Width */}
      {/* <Card>
        <CardContent sx={{ p: 2 }}> */}
      <Typography variant="subtitle1" gutterBottom>
        FHIR Operations
      </Typography>
      <Box sx={{ display: "flex", gap: 2 }}>
        {/* Read Operations - 33% */}
        <Box sx={{ width: "33%" }}>
          <Card variant="outlined">
            <CardContent sx={{ p: 2, textAlign: "center" }}>
              <Chip label="READ" size="small" color="success" sx={{ mb: 1 }} />
              <Typography variant="h4" color="primary.main" gutterBottom>
                {safeMetrics.operations.read.count.toLocaleString()}
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                operations
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  Ops/Sec:{" "}
                  {(safeMetrics.operations.read.count / 3600).toFixed(1)}
                </Typography>
                <Typography variant="body2">
                  Avg Latency: {safeMetrics.operations.read.avgLatency}ms
                </Typography>
                <Typography
                  variant="body2"
                  color={
                    safeMetrics.operations.read.successRate > 98
                      ? "success.main"
                      : safeMetrics.operations.read.successRate > 95
                      ? "warning.main"
                      : "error.main"
                  }
                >
                  Success: {safeMetrics.operations.read.successRate.toFixed(1)}%
                </Typography>
              </Box>
            </CardContent>
          </Card>
        </Box>

        {/* Create Operations - 33% */}
        <Box sx={{ width: "33%", borderLeft: "1px solid" }}>
          <Card variant="outlined">
            <CardContent sx={{ p: 2, textAlign: "center" }}>
              <Chip
                label="CREATE"
                size="small"
                color="primary"
                sx={{ mb: 1 }}
              />
              <Typography variant="h4" color="primary.main" gutterBottom>
                {safeMetrics.operations.create.count.toLocaleString()}
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                operations
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  Ops/Sec:{" "}
                  {(safeMetrics.operations.create.count / 3600).toFixed(1)}
                </Typography>
                <Typography variant="body2">
                  Avg Latency: {safeMetrics.operations.create.avgLatency}ms
                </Typography>
                <Typography
                  variant="body2"
                  color={
                    safeMetrics.operations.create.successRate > 98
                      ? "success.main"
                      : safeMetrics.operations.create.successRate > 95
                      ? "warning.main"
                      : "error.main"
                  }
                >
                  Success:{" "}
                  {safeMetrics.operations.create.successRate.toFixed(1)}%
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
                {safeMetrics.operations.search.count.toLocaleString()}
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                operations
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  Ops/Sec:{" "}
                  {(safeMetrics.operations.search.count / 3600).toFixed(1)}
                </Typography>
                <Typography variant="body2">
                  Avg Latency: {safeMetrics.operations.search.avgLatency}ms
                </Typography>
                <Typography
                  variant="body2"
                  color={
                    safeMetrics.operations.search.successRate > 98
                      ? "success.main"
                      : safeMetrics.operations.search.successRate > 95
                      ? "warning.main"
                      : "error.main"
                  }
                >
                  Success:{" "}
                  {safeMetrics.operations.search.successRate.toFixed(1)}%
                </Typography>
              </Box>
            </CardContent>
          </Card>
        </Box>
      </Box>
      {/* </CardContent>
      </Card> */}
    </Box>
  );
};

export default DashboardFhirServer;
