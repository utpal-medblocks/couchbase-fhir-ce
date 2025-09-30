import React, { useState, useEffect, useCallback, useMemo } from "react";
import {
  Box,
  Typography,
  Alert,
  CircularProgress,
  Paper,
  useTheme,
} from "@mui/material";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from "recharts";
import {
  lightBlue,
  orange,
  green,
  red,
  purple,
  teal,
} from "@mui/material/colors";
import {
  getBucketMetrics,
  type BucketMetricsResponse,
  type TimeRange,
  type BucketMetricData,
} from "../services/bucketMetricsService";

interface BucketMetricsChartsProps {
  connectionName: string;
  bucketName: string;
  timeRange: TimeRange;
}

interface ChartDataPoint {
  timestamp: number;
  time: string;
  [key: string]: number | string | null;
}

const BucketMetricsCharts: React.FC<BucketMetricsChartsProps> = ({
  connectionName,
  bucketName,
  timeRange,
}) => {
  const theme = useTheme();
  const [metrics, setMetrics] = useState<BucketMetricsResponse | null>(null);
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
      teal: theme.palette.mode === "dark" ? teal.A100 : teal.A700,
    }),
    [theme.palette.mode]
  );

  // Fetch metrics function with useCallback to prevent unnecessary re-renders
  const fetchMetrics = useCallback(
    async (isRefresh = false) => {
      if (!connectionName || !bucketName) return;

      setLoading(!isRefresh);
      setError(null);

      try {
        const data = await getBucketMetrics(
          connectionName,
          bucketName,
          timeRange
        );
        setMetrics(data);
      } catch (err) {
        console.error("Error fetching bucket metrics:", err);
        setError(
          err instanceof Error ? err.message : "Failed to fetch metrics"
        );
      } finally {
        setLoading(false);
      }
    },
    [connectionName, bucketName, timeRange]
  );

  // Initial fetch and refresh setup
  useEffect(() => {
    fetchMetrics();

    // Set up 20-second refresh
    const interval = setInterval(() => {
      fetchMetrics(true); // isRefresh = true to prevent loading spinner
    }, 20000);

    // Cleanup interval on unmount or dependency change
    return () => clearInterval(interval);
  }, [fetchMetrics]);

  const formatTimestamp = (timestamp: number, timeRange: TimeRange): string => {
    // Convert timestamp to milliseconds if it's in seconds
    const timestampMs = timestamp < 1e12 ? timestamp * 1000 : timestamp;
    const date = new Date(timestampMs);

    switch (timeRange) {
      case "MINUTE":
        // Format: 4:58:30 PM (every 30 seconds)
        return date.toLocaleTimeString("en-US", {
          hour12: true,
          hour: "numeric",
          minute: "2-digit",
          second: "2-digit",
        });
      case "HOUR":
        // Format: 4:30 PM, 5:00 PM (every 30 minutes)
        return date.toLocaleTimeString("en-US", {
          hour12: true,
          hour: "numeric",
          minute: "2-digit",
        });
      case "DAY":
        // Format: Aug 7, 12 PM (every 12 hours)
        return date.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
          hour: "numeric",
          hour12: true,
        });
      case "WEEK":
        // Format: Aug 1, Aug 3 (every 2 days)
        return date.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
        });
      case "MONTH":
        // Format: Jul 13, Jul 20 (every 7 days)
        return date.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
        });
      default:
        return date.toLocaleTimeString();
    }
  };

  const formatValue = (value: number | null, unit: string): string => {
    if (value === null || isNaN(value)) {
      return "N/A";
    }

    switch (unit) {
      case "%":
        return value.toFixed(1) + "%";
      case "GB":
        return value.toFixed(2) + " GB";
      case "ops/sec":
        return value.toFixed(1);
      case "ratio":
        // Handle scientific notation (e.g., 1.00e+02 = 100)
        // For ratios, the value should already be in percentage form from backend
        return value.toFixed(1) + "%";
      case "/sec":
        return value.toFixed(1);
      case "ms":
        return value.toFixed(2) + " ms";
      case "ns":
        return (value / 1000000).toFixed(2) + " ms";
      case "req/sec":
        return value.toFixed(1);
      default:
        return value.toString();
    }
  };

  const getTickInterval = (
    timeRange: TimeRange,
    dataLength: number
  ): number | "preserveStartEnd" => {
    // Optimize tick intervals based on data points and time range
    let maxTicks: number;

    switch (timeRange) {
      case "MINUTE":
        // For 9 data points, show 3 ticks
        maxTicks = 3;
        break;
      case "HOUR":
      case "DAY":
      case "WEEK":
      case "MONTH":
        // For 50 data points, show 3-4 ticks for readability
        maxTicks = 3;
        break;
      default:
        maxTicks = 3;
    }

    const interval = Math.max(1, Math.floor(dataLength / maxTicks));
    return interval;
  };

  const prepareChartData = (
    metricsData: BucketMetricData[],
    timestamps: number[]
  ): ChartDataPoint[] => {
    return timestamps.map((timestamp) => {
      const dataPoint: ChartDataPoint = {
        timestamp,
        time: formatTimestamp(timestamp, timeRange),
      };

      metricsData.forEach((metric) => {
        const point = metric.dataPoints.find(
          (dp) => dp.timestamp === timestamp
        );
        // Handle scientific notation values
        let value = point?.value ?? null;
        if (value !== null && typeof value === "number") {
          // Convert scientific notation to normal number
          value = Number(value);
        }
        dataPoint[metric.name] = value;
      });

      return dataPoint;
    });
  };

  // Define chart configurations - All as line charts
  const chartConfigs = [
    {
      title: "System",
      metrics: ["sys_cpu_utilization_rate", "sys_mem_actual_free"],
      colors: [chartColors.primary, chartColors.secondary],
      unit: "mixed", // Special case for mixed units (% and GB)
      type: "line" as const,
    },
    {
      title: "Cache Performance",
      metrics: ["kv_vb_resident_items_ratio", "kv_ep_cache_miss_ratio"],
      colors: [chartColors.success, chartColors.error],
      unit: "ratio",
      type: "line" as const,
    },
    {
      title: "Operations/sec",
      metrics: ["kv_ops", "kv_ops_get", "kv_ops_set"],
      colors: [chartColors.primary, chartColors.success, chartColors.warning],
      unit: "ops/sec",
      type: "line" as const,
    },
    {
      title: "Search",
      metrics: [
        "fts_total_queries",
        "fts_total_queries_rejected_by_herder",
        "fts_curr_batches_blocked_by_herder",
      ],
      colors: [chartColors.teal, chartColors.error, chartColors.info],
      unit: "/sec",
      type: "line" as const,
    },
  ];

  if (loading && !metrics) {
    return (
      <Box
        display="flex"
        justifyContent="center"
        alignItems="center"
        height="200px"
      >
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return (
      <Alert severity="error" sx={{ m: 2 }}>
        {error}
      </Alert>
    );
  }

  if (!metrics || !metrics.metrics.length) {
    return (
      <Alert severity="info" sx={{ m: 2 }}>
        No metrics data available
      </Alert>
    );
  }

  const chartData = prepareChartData(metrics.metrics, metrics.timestamps);

  const customTooltip = ({ active, payload, label }: any) => {
    if (!active || !payload || !payload.length) return null;

    return (
      <Paper sx={{ p: 1 }}>
        <Typography variant="subtitle2" gutterBottom>
          {label}
        </Typography>
        {payload.map((entry: any, index: number) => {
          const metric = metrics.metrics.find((m) => m.name === entry.dataKey);
          return (
            <Box key={index} sx={{ color: entry.color }}>
              {metric?.label}: {formatValue(entry.value, metric?.unit || "")}
            </Box>
          );
        })}
      </Paper>
    );
  };

  return (
    <Box>
      {/* Charts Grid - 2x2 layout for 4 bucket metrics */}
      <Box
        display="grid"
        gridTemplateColumns="repeat(2, 1fr)"
        gap={2}
        sx={{ maxWidth: "100%" }}
      >
        {chartConfigs.map((config, index) => {
          const chartMetrics = metrics.metrics.filter((m) =>
            config.metrics.includes(m.name)
          );

          return (
            <Paper key={index} sx={{ p: 1 }}>
              <Typography variant="subtitle2" gutterBottom>
                {config.title}
              </Typography>
              <ResponsiveContainer width="100%" height={200}>
                <LineChart
                  data={chartData}
                  margin={{ top: 5, right: 5, left: 5, bottom: 10 }}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="#e0e0e0" />
                  <XAxis
                    dataKey="time"
                    tick={{ fontSize: 12, fill: "currentColor" }}
                    axisLine={{ stroke: "#e0e0e0" }}
                    tickLine={{ stroke: "#e0e0e0" }}
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
                    height={timeRange === "DAY" ? 50 : 30}
                  />
                  <YAxis
                    tick={{ fontSize: 10, fill: "currentColor" }}
                    tickFormatter={(value) => {
                      if (config.unit === "mixed") {
                        // For mixed units, format based on value range (CPU % usually 0-100, RAM GB usually larger)
                        return value < 100
                          ? value.toFixed(1)
                          : value.toFixed(0);
                      }
                      return formatValue(value, config.unit);
                    }}
                    axisLine={{ stroke: "#e0e0e0" }}
                    tickLine={{ stroke: "#e0e0e0" }}
                    width={60}
                    domain={["auto", "auto"]}
                  />
                  <Tooltip content={customTooltip} />
                  {chartMetrics.length > 1 && (
                    <Legend
                      wrapperStyle={{ fontSize: "10px" }}
                      formatter={(value) => {
                        const metric = metrics.metrics.find(
                          (m) => m.name === value
                        );
                        return metric?.label || value;
                      }}
                    />
                  )}
                  {chartMetrics.map((metric, metricIndex) => (
                    <Line
                      key={metric.name}
                      type="monotone"
                      dataKey={metric.name}
                      stroke={config.colors[metricIndex]}
                      strokeWidth={2}
                      dot={false}
                      connectNulls={false}
                      name={metric.label}
                      isAnimationActive={false}
                    />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </Paper>
          );
        })}
      </Box>
    </Box>
  );
};

export default BucketMetricsCharts;
