import React, { useEffect } from "react";
import {
  Box,
  Typography,
  Card,
  CardContent,
  Chip,
  Alert,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
} from "@mui/material";
import { GaugeChart } from "../../components/GuageChart";
import { tableCellStyle } from "../../styles/styles";
import { useFhirStore } from "../../store/fhirStore";

const DashboardFhirServer: React.FC = () => {
  const { metrics, error, retrievedAt, fetchMetrics } = useFhirStore();

  useEffect(() => {
    // Initial fetch when component mounts
    fetchMetrics();
  }, [fetchMetrics]);

  if (error) {
    return (
      <Alert severity="error" sx={{ mb: 2 }}>
        {error}
      </Alert>
    );
  }

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

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      {/* Version Info - Full Width */}
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
            {metrics.version.serverVersion}
          </Typography>
        </Box>
        <Box sx={{ flex: 1, textAlign: "center" }}>
          <Typography variant="body2" color="text.secondary">
            Build
          </Typography>
          <Typography variant="body1">{metrics.version.buildNumber}</Typography>
        </Box>
        {retrievedAt && (
          <Box sx={{ flex: 1, textAlign: "center" }}>
            <Typography variant="body2" color="text.secondary">
              Last Updated
            </Typography>
            <Typography variant="body1">
              {retrievedAt.toLocaleTimeString()}
            </Typography>
          </Box>
        )}
      </Box>

      {/* Server Status and System Resources - Full Width */}
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
                    {metrics.server.status}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Uptime</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {metrics.server.uptime}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>JVM Threads</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {metrics.server.jvmThreads}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Current Ops/Sec</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {metrics.overall.currentOpsPerSec.toFixed(1)}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Total Ops</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {metrics.overall.totalOperations.toLocaleString()}
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
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
              <GaugeChart name="CPU" value={metrics.server.cpuUsage} />
            </Box>

            {/* Memory Gauge - 33% */}
            <Box sx={{ width: "33%", position: "relative" }}>
              <GaugeChart name="RAM" value={metrics.server.memoryUsage} />
            </Box>

            {/* Disk Gauge - 33% */}
            <Box sx={{ width: "33%", position: "relative" }}>
              <GaugeChart name="Disk" value={metrics.server.diskUsage} />
            </Box>
          </Box>
        </Box>
      </Box>

      {/* FHIR Operations - Full Width */}
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
                {metrics.operations.read.count.toLocaleString()}
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                operations
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  Ops/Sec: {(metrics.operations.read.count / 3600).toFixed(1)}
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
                {metrics.operations.create.count.toLocaleString()}
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                operations
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  Ops/Sec: {(metrics.operations.create.count / 3600).toFixed(1)}
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
                  Success: {metrics.operations.create.successRate.toFixed(1)}%
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
              <Typography variant="body2" color="text.secondary" gutterBottom>
                operations
              </Typography>
              <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}>
                <Typography variant="body2">
                  Ops/Sec: {(metrics.operations.search.count / 3600).toFixed(1)}
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
                  Success: {metrics.operations.search.successRate.toFixed(1)}%
                </Typography>
              </Box>
            </CardContent>
          </Card>
        </Box>
      </Box>
    </Box>
  );
};

export default DashboardFhirServer;
