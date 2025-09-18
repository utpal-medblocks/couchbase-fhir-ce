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
import { haproxyMetricsService } from "../../services/haproxyMetricsService";

const DashboardFhirServer: React.FC = () => {
  const { metrics, error, retrievedAt, fetchMetrics } = useFhirStore();
  const [haproxyMetrics, setHaproxyMetrics] = React.useState<any>(null);
  const [haproxyError, setHaproxyError] = React.useState<string | null>(null);

  useEffect(() => {
    // Initial fetch when component mounts
    fetchMetrics();
    fetchHaproxyMetrics();
  }, [fetchMetrics]);

  const fetchHaproxyMetrics = async () => {
    try {
      const data = await haproxyMetricsService.getDashboardMetrics();
      setHaproxyMetrics(data);
      setHaproxyError(null);
    } catch (err) {
      setHaproxyError(
        err instanceof Error ? err.message : "Failed to fetch HAProxy metrics"
      );
      setHaproxyMetrics(null);
    }
  };

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

      {/* Note: FHIR operation-level metrics (READ/CREATE/SEARCH) would require 
          application-level instrumentation, not available from HAProxy */}

      {/* HAProxy Load Balancer Metrics */}
      <Typography variant="subtitle1" gutterBottom sx={{ mt: 3 }}>
        Load Balancer (HAProxy)
      </Typography>
      {haproxyError ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          HAProxy metrics unavailable: {haproxyError}
        </Alert>
      ) : haproxyMetrics ? (
        <Box sx={{ display: "flex", gap: 2 }}>
          {/* Total Requests */}
          <Box sx={{ width: "25%" }}>
            <Card variant="outlined">
              <CardContent sx={{ p: 2, textAlign: "center" }}>
                <Chip
                  label="REQUESTS"
                  size="small"
                  color="info"
                  sx={{ mb: 1 }}
                />
                <Typography variant="h5" color="primary.main" gutterBottom>
                  {haproxyMetrics.totalRequests.toLocaleString()}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  total requests
                </Typography>
              </CardContent>
            </Card>
          </Box>

          {/* Success Rate */}
          <Box sx={{ width: "25%" }}>
            <Card variant="outlined">
              <CardContent sx={{ p: 2, textAlign: "center" }}>
                <Chip
                  label="SUCCESS"
                  size="small"
                  color="success"
                  sx={{ mb: 1 }}
                />
                <Typography variant="h5" color="success.main" gutterBottom>
                  {haproxyMetrics.successRate.toFixed(1)}%
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  success rate
                </Typography>
              </CardContent>
            </Card>
          </Box>

          {/* Current Sessions */}
          <Box sx={{ width: "25%" }}>
            <Card variant="outlined">
              <CardContent sx={{ p: 2, textAlign: "center" }}>
                <Chip
                  label="SESSIONS"
                  size="small"
                  color="warning"
                  sx={{ mb: 1 }}
                />
                <Typography variant="h5" color="warning.main" gutterBottom>
                  {haproxyMetrics.currentSessions}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  active sessions
                </Typography>
              </CardContent>
            </Card>
          </Box>

          {/* Status */}
          <Box sx={{ width: "25%" }}>
            <Card variant="outlined">
              <CardContent sx={{ p: 2, textAlign: "center" }}>
                <Chip
                  label="STATUS"
                  size="small"
                  color={
                    haproxyMetrics.backendStatus === "UP" ? "success" : "error"
                  }
                  sx={{ mb: 1 }}
                />
                <Typography
                  variant="h6"
                  color={
                    haproxyMetrics.backendStatus === "UP"
                      ? "success.main"
                      : "error.main"
                  }
                  gutterBottom
                >
                  {haproxyMetrics.backendStatus}
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  backend status
                </Typography>
              </CardContent>
            </Card>
          </Box>
        </Box>
      ) : (
        <Box sx={{ textAlign: "center", py: 2 }}>
          <Typography variant="body2" color="text.secondary">
            Loading HAProxy metrics...
          </Typography>
        </Box>
      )}
    </Box>
  );
};

export default DashboardFhirServer;
