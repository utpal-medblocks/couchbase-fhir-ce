import React, { useState, useEffect } from "react";
import {
  Box,
  Typography,
  FormControl,
  Select,
  MenuItem,
  Grid,
  Paper,
  CircularProgress,
  Alert,
} from "@mui/material";
import type { SelectChangeEvent } from "@mui/material";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
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
  const [timeRange, setTimeRange] = useState<TimeRange>("HOUR");
  const [metrics, setMetrics] = useState<FtsMetricsResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchMetrics = async () => {
    if (!connectionName || !bucketName || !indexName) return;

    setLoading(true);
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
      setError(err instanceof Error ? err.message : "Failed to fetch metrics");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchMetrics();
  }, [connectionName, bucketName, indexName, timeRange]);

  const handleTimeRangeChange = (event: SelectChangeEvent) => {
    setTimeRange(event.target.value as TimeRange);
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
    const maxTicks = 8; // Maximum number of ticks to show
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

  const renderChart = (
    title: string,
    metricNames: string[],
    color: string,
    unit: string
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
      <ResponsiveContainer width="100%" height={300}>
        <LineChart data={chartData}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis
            dataKey="time"
            tick={{ fontSize: 12 }}
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
          />
          <YAxis
            tick={{ fontSize: 12 }}
            tickFormatter={(value) => formatValue(value, unit)}
          />
          <Tooltip
            formatter={(value: number) => [formatValue(value, unit), title]}
            labelFormatter={(label) => `Time: ${label}`}
          />
          <Legend />
          {relevantMetrics.map((metric) => (
            <Line
              key={metric.name}
              type="monotone"
              dataKey={metric.name}
              stroke={color}
              strokeWidth={2}
              dot={false}
              name={metric.label}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    );
  };

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
      <Box
        display="flex"
        justifyContent="space-between"
        alignItems="center"
        mb={3}
      >
        <Typography variant="h6">FTS Index Metrics</Typography>
        <FormControl size="small" sx={{ minWidth: 150 }}>
          <Select value={timeRange} onChange={handleTimeRangeChange}>
            {TIME_RANGE_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      </Box>

      {/* Charts Grid */}
      <Box
        display="grid"
        gridTemplateColumns="repeat(auto-fit, minmax(400px, 1fr))"
        gap={3}
      >
        {/* Query Metrics */}
        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            Total Queries
          </Typography>
          {renderChart(
            "Total Queries",
            ["fts_total_queries"],
            "#1976d2",
            "queries"
          )}
        </Paper>

        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            Average Query Latency
          </Typography>
          {renderChart(
            "Avg Latency",
            ["fts_avg_queries_latency"],
            "#ed6c02",
            "ms"
          )}
        </Paper>

        {/* Document Count */}
        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            Document Count
          </Typography>
          {renderChart("Document Count", ["fts_doc_count"], "#2e7d32", "docs")}
        </Paper>

        {/* Storage Metrics */}
        <Paper sx={{ p: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            Disk Usage
          </Typography>
          {renderChart(
            "Disk Usage",
            ["fts_num_bytes_used_disk"],
            "#9c27b0",
            "bytes"
          )}
        </Paper>

        <Paper sx={{ p: 2, gridColumn: "1 / -1" }}>
          <Typography variant="subtitle1" gutterBottom>
            RAM Usage
          </Typography>
          {renderChart(
            "RAM Usage",
            ["fts_num_bytes_used_ram"],
            "#d32f2f",
            "bytes"
          )}
        </Paper>
      </Box>
    </Box>
  );
};

export default FtsMetricsCharts;
