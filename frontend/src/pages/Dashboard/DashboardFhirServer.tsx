import React, { useEffect, useState, useCallback } from "react";
import {
  Box,
  Typography,
  Chip,
  Alert,
  FormControl,
  Select,
  MenuItem,
  InputLabel,
} from "@mui/material";
import type { SelectChangeEvent } from "@mui/material";
import { useFhirStore } from "../../store/fhirStore";
import { haproxyMetricsService } from "../../services/haproxyMetricsService";
import HAProxyMetricsCharts from "../../components/HAProxyMetricsCharts";
import {
  TIME_RANGE_OPTIONS,
  type TimeRange,
} from "../../services/bucketMetricsService";
import { getStoredTimeRange, storeTimeRange } from "../../utils/sessionStorage";
import { fetchBackendVersion } from "../../services/versionService";

const DashboardFhirServer: React.FC = () => {
  // Mock metrics data since we removed ActuatorAggregatorService
  const metrics = {
    server: {
      status: "UP",
      uptime: "Running",
    },
  };
  const error = null;
  const [haproxyMetrics, setHaproxyMetrics] = React.useState<any>(null);
  const [haproxyError, setHaproxyError] = React.useState<string | null>(null);
  const [backendVersion, setBackendVersion] = useState<string>("...");

  // State for time range with session storage
  const [timeRange, setTimeRange] = useState<TimeRange>(() =>
    getStoredTimeRange("HOUR")
  );

  const handleTimeRangeChange = useCallback((event: SelectChangeEvent) => {
    const newTimeRange = event.target.value as TimeRange;
    setTimeRange(newTimeRange);
    storeTimeRange(newTimeRange);
  }, []);

  useEffect(() => {
    // Initial fetch when component mounts
    fetchHaproxyMetrics();
    fetchVersion();
  }, []);

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

  const fetchVersion = async () => {
    try {
      const { version } = await fetchBackendVersion();
      setBackendVersion(version);
    } catch (e) {
      setBackendVersion("unknown");
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
      {/* Couchbase FHIR Server Details */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "flex-start",
          alignItems: "center",
          gap: 4,
          p: 0,
        }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Version:
          </Typography>
          <Typography variant="body1">{backendVersion}</Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Status:
          </Typography>
          <Chip
            label={metrics.server.status}
            color={metrics.server.status === "UP" ? "success" : "error"}
            size="small"
          />
        </Box>
      </Box>

      {/* System & Load Balancer Metrics */}
      <Box>
        {/* Header with Range Selector */}
        <Box
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            mb: 1,
          }}
        >
          <Typography variant="subtitle1" component="div">
            System & Load Balancer Metrics
          </Typography>
          <FormControl size="small" sx={{ minWidth: 120 }}>
            <InputLabel>Range</InputLabel>
            <Select
              value={timeRange}
              onChange={handleTimeRangeChange}
              label="Range"
            >
              {TIME_RANGE_OPTIONS.map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label.replace("Last ", "")}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
        <HAProxyMetricsCharts timeRange={timeRange} />
      </Box>
    </Box>
  );
};

export default DashboardFhirServer;
