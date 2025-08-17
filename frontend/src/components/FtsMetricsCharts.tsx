import React, { useState, useEffect, useCallback, useMemo } from "react";
import {
  Box,
  Typography,
  FormControl,
  Select,
  MenuItem,
  InputLabel,
  Paper,
  CircularProgress,
  Alert,
  useTheme,
} from "@mui/material";
import { lightBlue, orange, green, red, purple } from "@mui/material/colors";
import type { SelectChangeEvent } from "@mui/material";
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import {
  getFtsMetrics,
  TIME_RANGE_OPTIONS,
} from "../services/ftsMetricsService";
import type {
  FtsMetricsResponse,
  TimeRange,
  FtsMetricData,
} from "../services/ftsMetricsService";
import { getStoredTimeRange, storeTimeRange } from "../utils/sessionStorage";

interface FtsMetricsChartsProps {
  connectionName: string;
  bucketName: string;
  indexName: string;
}

interface ChartDataPoint {
  timestamp: number;
  time: string;
  [key: string]: number | string;
}

const FtsMetricsCharts: React.FC<FtsMetricsChartsProps> = ({
  connectionName,
  bucketName,
  indexName,
}) => {
  const theme = useTheme();
  const [timeRange, setTimeRange] = useState<TimeRange>(() =>
    getStoredTimeRange("HOUR")
  );
  const [metrics, setMetrics] = useState<FtsMetricsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Define theme-aware colors (memoized to prevent re-renders)
  const chartColors = useMemo(
    () => ({
      primary: theme.palette.mode === "dark" ? lightBlue.A100 : lightBlue.A700,
      secondary: theme.palette.mode === "dark" ? orange.A100 : orange.A700,
      success: theme.palette.mode === "dark" ? green.A100 : green.A700,
      warning: theme.palette.mode === "dark" ? orange.A100 : orange.A700,
      error: theme.palette.mode === "dark" ? red.A100 : red.A700,
      info: theme.palette.mode === "dark" ? purple.A100 : purple.A700,
    }),
    [theme.palette.mode]
  );
  const tooltipBackground = theme.palette.mode === "dark" ? "#000" : "#fff";
  const tooltipTextColor = theme.palette.mode === "dark" ? "#fff" : "#000";
  const fetchMetrics = useCallback(
    async (isRefresh = false) => {
      if (!connectionName || !bucketName || !indexName) return;

      // Only show loading spinner on initial load, not on refresh
      if (!isRefresh) {
        setLoading(true);
      }
      setError(null);

      try {
        const data = await getFtsMetrics(
          connectionName,
          bucketName,
          indexName,
          timeRange
        );
        setMetrics(data);
      } catch (err) {
        setError(
          err instanceof Error ? err.message : "Failed to fetch metrics"
        );
      } finally {
        if (!isRefresh) {
          setLoading(false);
        }
      }
    },
    [connectionName, bucketName, indexName, timeRange]
  );

  useEffect(() => {
    fetchMetrics(false); // Initial load with loading spinner

    // Set up auto-refresh every 20 seconds
    const interval = setInterval(() => {
      fetchMetrics(true); // Refresh without loading spinner
    }, 20000);

    // Cleanup interval on unmount or dependency change
    return () => clearInterval(interval);
  }, [fetchMetrics]);

  const handleTimeRangeChange = (event: SelectChangeEvent) => {
    const newTimeRange = event.target.value as TimeRange;
    setTimeRange(newTimeRange);
    storeTimeRange(newTimeRange);
  };

  const formatTimestamp = (timestamp: number, timeRange: TimeRange): string => {
    const date = new Date(timestamp);

    switch (timeRange) {
      case "MINUTE":
        // Format: 4:58:30 PM (every 30 seconds)
        return date.toLocaleTimeString([], {
          hour: "numeric",
          minute: "2-digit",
          second: "2-digit",
          hour12: true,
        });
      case "HOUR":
        // Format: 4:30 PM (every 30 minutes)
        return date.toLocaleTimeString([], {
          hour: "numeric",
          minute: "2-digit",
          hour12: true,
        });
      case "DAY":
        // Format: Aug 7, 12 PM (every 12 hours with date)
        return (
          date.toLocaleDateString([], {
            month: "short",
            day: "numeric",
          }) +
          ", " +
          date.toLocaleTimeString([], {
            hour: "numeric",
            hour12: true,
          })
        );
      case "WEEK":
        // Format: Aug 1, Aug 3 (every 2 days)
        return date.toLocaleDateString([], {
          month: "short",
          day: "numeric",
        });
      case "MONTH":
        // Format: Jul 13, Jul 20 (every 7 days)
        return date.toLocaleDateString([], {
          month: "short",
          day: "numeric",
        });
      default:
        return date.toLocaleTimeString();
    }
  };

  const getTickInterval = (
    timeRange: TimeRange,
    dataLength: number
  ): number | "preserveStartEnd" => {
    // Calculate optimal tick interval based on time range and data points
    const maxTicks = 3; // Maximum number of ticks to show (reduced from 4)
    const interval = Math.max(1, Math.floor(dataLength / maxTicks));

    switch (timeRange) {
      case "MINUTE":
        return interval; // Show every nth point for 30-second intervals
      case "HOUR":
        return interval; // Show every nth point for 30-minute intervals
      case "DAY":
        return interval; // Show every nth point for 12-hour intervals
      case "WEEK":
        return interval; // Show every nth point for 2-day intervals
      case "MONTH":
        return interval; // Show every nth point for 7-day intervals
      default:
        return "preserveStartEnd";
    }
  };

  const formatValue = (value: number, unit: string): string => {
    if (unit === "bytes") {
      if (value >= 1024 * 1024 * 1024) {
        return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
      } else if (value >= 1024 * 1024) {
        return `${(value / (1024 * 1024)).toFixed(2)} MB`;
      } else if (value >= 1024) {
        return `${(value / 1024).toFixed(2)} KB`;
      }
      return `${value} B`;
    }
    if (unit === "ms") {
      return `${value.toFixed(2)} ms`;
    }
    if (unit === "docs") {
      return Math.round(value).toLocaleString(); // Always whole numbers for document count
    }
    return value.toLocaleString();
  };

  const prepareChartData = (metricData: FtsMetricData[]): ChartDataPoint[] => {
    if (!metricData.length) return [];

    // Get all unique timestamps
    const allTimestamps = new Set<number>();
    metricData.forEach((metric) => {
      metric.values.forEach((point) => allTimestamps.add(point.timestamp));
    });

    // Sort timestamps
    const sortedTimestamps = Array.from(allTimestamps).sort();

    // Create chart data points
    return sortedTimestamps.map((timestamp) => {
      const dataPoint: ChartDataPoint = {
        timestamp,
        time: formatTimestamp(timestamp, timeRange),
      };

      metricData.forEach((metric) => {
        const point = metric.values.find((p) => p.timestamp === timestamp);
        dataPoint[metric.name] = point?.value ?? 0;
      });

      return dataPoint;
    });
  };

  const renderChart = useCallback(
    (
      title: string,
      metricNames: string[],
      color: string,
      unit: string,
      chartType: "line" | "bar" = "line"
    ) => {
      if (!metrics || !metrics.data.length) {
        return (
          <Box
            display="flex"
            justifyContent="center"
            alignItems="center"
            height={300}
          >
            <Typography color="text.secondary">No data available</Typography>
          </Box>
        );
      }

      const relevantMetrics = metrics.data.filter((m) =>
        metricNames.includes(m.name)
      );
      const chartData = prepareChartData(relevantMetrics);

      return (
        <ResponsiveContainer width="100%" height={180}>
          {chartType === "bar" ? (
            <BarChart
              data={chartData}
              margin={{ top: 5, right: 10, left: 10, bottom: 5 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="#e0e0e0" />
              <XAxis
                dataKey="time"
                tick={{ fontSize: 12, fill: "currentColor" }}
                interval={getTickInterval(timeRange, chartData.length)}
                angle={
                  timeRange === "DAY" ||
                  timeRange === "WEEK" ||
                  timeRange === "MONTH"
                    ? -45
                    : 0
                }
                textAnchor={
                  timeRange === "DAY" ||
                  timeRange === "WEEK" ||
                  timeRange === "MONTH"
                    ? "end"
                    : "middle"
                }
                height={
                  timeRange === "DAY" ||
                  timeRange === "WEEK" ||
                  timeRange === "MONTH"
                    ? 80
                    : 60
                }
                axisLine={{ stroke: "#e0e0e0" }}
                tickLine={{ stroke: "#e0e0e0" }}
              />
              <YAxis
                tick={{ fontSize: 12, fill: "currentColor" }}
                tickFormatter={(value) => formatValue(value, unit)}
                axisLine={{ stroke: "#e0e0e0" }}
                tickLine={{ stroke: "#e0e0e0" }}
                tickCount={8}
                width={60}
                domain={
                  unit === "docs"
                    ? [0, "dataMax"]
                    : unit === "ms"
                    ? [0, "dataMax"]
                    : ["auto", "auto"]
                }
                allowDecimals={unit !== "docs"}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: tooltipBackground,
                  border: "1px solid #e0e0e0",
                  borderRadius: "4px",
                  color: tooltipTextColor,
                  fontSize: "12px",
                  padding: "4px 6px",
                }}
                formatter={(value: number) => [formatValue(value, unit), title]}
                labelFormatter={(label) => `${label}`}
                labelStyle={{ color: tooltipTextColor, fontSize: "12px" }}
              />
              {relevantMetrics.map((metric) => (
                <Bar
                  key={metric.name}
                  dataKey={metric.name}
                  fill={color}
                  name={metric.label}
                  isAnimationActive={false}
                />
              ))}
            </BarChart>
          ) : (
            <LineChart
              data={chartData}
              margin={{ top: 5, right: 10, left: 10, bottom: 5 }}
            >
              <CartesianGrid strokeDasharray="3 3" stroke="#e0e0e0" />
              <XAxis
                dataKey="time"
                tick={{ fontSize: 12, fill: "currentColor" }}
                interval={getTickInterval(timeRange, chartData.length)}
                angle={
                  timeRange === "DAY" ||
                  timeRange === "WEEK" ||
                  timeRange === "MONTH"
                    ? -45
                    : 0
                }
                textAnchor={
                  timeRange === "DAY" ||
                  timeRange === "WEEK" ||
                  timeRange === "MONTH"
                    ? "end"
                    : "middle"
                }
                height={
                  timeRange === "DAY" ||
                  timeRange === "WEEK" ||
                  timeRange === "MONTH"
                    ? 80
                    : 60
                }
                axisLine={{ stroke: "#e0e0e0" }}
                tickLine={{ stroke: "#e0e0e0" }}
              />
              <YAxis
                tick={{ fontSize: 12, fill: "currentColor" }}
                tickFormatter={(value) => formatValue(value, unit)}
                axisLine={{ stroke: "#e0e0e0" }}
                tickLine={{ stroke: "#e0e0e0" }}
                tickCount={8}
                width={60}
                domain={
                  unit === "docs"
                    ? [0, "dataMax"]
                    : unit === "ms"
                    ? [0, "dataMax"]
                    : ["auto", "auto"]
                }
                allowDecimals={unit !== "docs"}
              />
              <Tooltip
                contentStyle={{
                  backgroundColor: tooltipBackground,
                  border: "1px solid #e0e0e0",
                  borderRadius: "4px",
                  color: tooltipTextColor,
                  fontSize: "12px",
                  padding: "4px 6px",
                }}
                formatter={(value: number) => [formatValue(value, unit), title]}
                labelFormatter={(label) => `${label}`}
                labelStyle={{ color: tooltipTextColor, fontSize: "12px" }}
              />
              {relevantMetrics.map((metric) => (
                <Line
                  key={metric.name}
                  type="monotone"
                  dataKey={metric.name}
                  stroke={color}
                  strokeWidth={2.5}
                  dot={false}
                  name={metric.label}
                  activeDot={{ r: 4, fill: color }}
                  isAnimationActive={false}
                />
              ))}
            </LineChart>
          )}
        </ResponsiveContainer>
      );
    },
    [metrics, timeRange]
  );

  if (loading) {
    return (
      <Box
        display="flex"
        justifyContent="center"
        alignItems="center"
        height={400}
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

  return (
    <Box>
      {/* Time Range Selector */}
      <Box display="flex" justifyContent="flex-end" alignItems="center" mb={2}>
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <InputLabel>Choose Range</InputLabel>
          <Select
            value={timeRange}
            onChange={handleTimeRangeChange}
            label="Choose Range"
          >
            {TIME_RANGE_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label.replace("Last ", "")}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      </Box>

      {/* Charts Grid - 2x2 layout for 4 core metrics */}
      <Box
        display="grid"
        gridTemplateColumns="repeat(2, 1fr)"
        gap={2}
        sx={{ maxWidth: "100%" }}
      >
        <Paper sx={{ p: 1 }}>
          <Typography variant="subtitle2" gutterBottom>
            Search Rate (per second)
          </Typography>
          {renderChart(
            "Total Queries",
            ["fts_total_grpc_queries"],
            chartColors.primary,
            "queries",
            "bar"
          )}
        </Paper>

        <Paper sx={{ p: 1 }}>
          <Typography variant="subtitle2" gutterBottom>
            Average Search Latency (ms)
          </Typography>
          {renderChart(
            "Avg Latency",
            ["fts_avg_grpc_queries_latency"],
            chartColors.secondary,
            "ms",
            "bar"
          )}
        </Paper>

        <Paper sx={{ p: 1 }}>
          <Typography variant="subtitle2" gutterBottom>
            Document Count
          </Typography>
          {renderChart(
            "Document Count",
            ["fts_doc_count"],
            chartColors.success,
            "docs",
            "line"
          )}
        </Paper>

        <Paper sx={{ p: 1 }}>
          <Typography variant="subtitle2" gutterBottom>
            Disk Usage
          </Typography>
          {renderChart(
            "Disk Usage",
            ["fts_num_bytes_used_disk"],
            chartColors.info,
            "bytes",
            "line"
          )}
        </Paper>
      </Box>
    </Box>
  );
};

export default FtsMetricsCharts;
