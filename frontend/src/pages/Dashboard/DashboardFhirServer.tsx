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
  // Real server status from health endpoint
  const [serverStatus, setServerStatus] = useState<"UP" | "DOWN" | "UNKNOWN">(
    "UNKNOWN"
  );
  const [haproxyAvailable, setHaproxyAvailable] = useState<boolean>(true);
  const error = null;
  const [haproxyMetrics, setHaproxyMetrics] = React.useState<any>(null);
  const [haproxyError, setHaproxyError] = React.useState<string | null>(null);
  const [backendVersion, setBackendVersion] = useState<string>("...");
  const [buildTime, setBuildTime] = useState<string | undefined>(undefined);

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
    fetchServerHealth();
    fetchHaproxyMetrics();
    fetchVersion();

    // Poll server health every 10 seconds
    const healthInterval = setInterval(fetchServerHealth, 10000);

    return () => clearInterval(healthInterval);
  }, []);

  const fetchServerHealth = async () => {
    try {
      const response = await fetch("/health");
      setServerStatus(response.ok ? "UP" : "DOWN");
    } catch (err) {
      setServerStatus("DOWN");
    }
  };

  const fetchHaproxyMetrics = async () => {
    try {
      const data = await haproxyMetricsService.getDashboardMetrics();
      setHaproxyMetrics(data);
      setHaproxyError(null);
      setHaproxyAvailable(true);
    } catch (err) {
      setHaproxyError(
        err instanceof Error ? err.message : "Failed to fetch HAProxy metrics"
      );
      setHaproxyMetrics(null);
      // If HAProxy endpoint fails, we're probably in dev mode without HAProxy
      setHaproxyAvailable(false);
    }
  };

  const fetchVersion = async () => {
    try {
      const versionInfo = await fetchBackendVersion();
      setBackendVersion(versionInfo.version);
      setBuildTime(versionInfo.buildTime);
    } catch (e) {
      setBackendVersion("unknown");
      setBuildTime(undefined);
    }
  };

  // Format build time for display
  const formatBuildTime = (buildTime?: string): string | undefined => {
    if (!buildTime) return undefined;
    try {
      // Parse ISO 8601 timestamp and format as readable date
      const date = new Date(buildTime);
      return date.toLocaleString("en-US", {
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
        timeZoneName: "short",
      });
    } catch {
      return buildTime; // Return original if parsing fails
    }
  };

  if (error) {
    return (
      <Alert severity="error" sx={{ mb: 2 }}>
        {error}
      </Alert>
    );
  }

  // Removed loading check - we show status immediately (even if UNKNOWN)

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
          <Typography variant="body1">
            {backendVersion}
            {buildTime && (
              <Typography
                component="span"
                variant="caption"
                color="text.secondary"
                sx={{ ml: 1 }}
              >
                (built: {formatBuildTime(buildTime)})
              </Typography>
            )}
          </Typography>
        </Box>
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Status:
          </Typography>
          <Chip
            label={serverStatus}
            color={
              serverStatus === "UP"
                ? "success"
                : serverStatus === "DOWN"
                ? "error"
                : "default"
            }
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
          {haproxyAvailable && (
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
          )}
        </Box>

        {haproxyAvailable ? (
          <HAProxyMetricsCharts timeRange={timeRange} />
        ) : (
          <Alert severity="info" sx={{ mt: 1 }}>
            Load balancer metrics unavailable. Running in dev mode without
            HAProxy.
          </Alert>
        )}
      </Box>
    </Box>
  );
};

export default DashboardFhirServer;
