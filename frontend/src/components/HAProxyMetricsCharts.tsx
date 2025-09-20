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
  BarChart,
  Bar,
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
import { type TimeRange } from "../services/bucketMetricsService";
import {
  haproxyMetricsService,
  type HaproxyTimeSeriesResponse,
  type MetricDataPoint,
} from "../services/haproxyMetricsService";

interface HAProxyMetricsChartsProps {
  timeRange?: TimeRange;
}

interface ChartDataPoint {
  timestamp: number;
  time: string;
  [key: string]: number | string | null;
}

const HAProxyMetricsCharts: React.FC<HAProxyMetricsChartsProps> = ({
  timeRange = "HOUR",
}) => {
  const theme = useTheme();
  const [metricsData, setMetricsData] =
    useState<HaproxyTimeSeriesResponse | null>(null);
  const [loading, setLoading] = useState(true);
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

  // Fetch metrics data
  const fetchMetrics = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await haproxyMetricsService.getTimeSeriesMetrics();
      setMetricsData(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to fetch metrics");
      setMetricsData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchMetrics();

    // Set up polling every 30 seconds
    const interval = setInterval(fetchMetrics, 30000);
    return () => clearInterval(interval);
  }, [fetchMetrics, timeRange]); // Re-fetch when timeRange changes

  // Convert MetricDataPoint[] to ChartDataPoint[] format with backfilling
  const convertToChartData = (
    dataPoints: MetricDataPoint[],
    targetRange: TimeRange
  ): ChartDataPoint[] => {
    if (dataPoints.length === 0) return [];

    const targetCounts = { MINUTE: 10, HOUR: 60, DAY: 60, WEEK: 60, MONTH: 60 };
    const targetCount = targetCounts[targetRange];

    // If we have enough data points, use them as-is
    if (dataPoints.length >= targetCount) {
      return dataPoints.map((point) => ({
        timestamp: point.timestamp,
        time: new Date(point.timestamp).toLocaleTimeString("en-US", {
          hour: "numeric",
          minute: "2-digit",
        }),
        cpu: point.cpu,
        memory: point.memory,
        disk: point.disk,
        currentRate: point.ops,
        maxRate: point.rate_max,
        currentConnections: point.scur,
        currentLatency: point.latency,
        maxLatency: point.latency_max,
        hrsp_5xx: point.hrsp_5xx,
        hrsp_4xx: point.hrsp_4xx,
        errorPercent: point.error_percent,
      }));
    }

    // Backfill with null values
    const result: ChartDataPoint[] = [];
    const firstPoint = dataPoints[0];
    const intervals = {
      MINUTE: 6000,
      HOUR: 60000,
      DAY: 1440000,
      WEEK: 10080000,
      MONTH: 43200000,
    }; // ms
    const interval = intervals[targetRange];

    // Create backfilled points with null values
    for (let i = targetCount - 1; i >= dataPoints.length; i--) {
      const timestamp =
        firstPoint.timestamp - (i - dataPoints.length + 1) * interval;
      result.push({
        timestamp,
        time: new Date(timestamp).toLocaleTimeString("en-US", {
          hour: "numeric",
          minute: "2-digit",
        }),
        cpu: null,
        memory: null,
        disk: null,
        currentRate: null,
        maxRate: null,
        currentConnections: null,
        currentLatency: null,
        maxLatency: null,
        hrsp_5xx: null,
        hrsp_4xx: null,
        errorPercent: null,
      });
    }

    // Add real data points
    dataPoints.forEach((point) => {
      result.push({
        timestamp: point.timestamp,
        time: new Date(point.timestamp).toLocaleTimeString("en-US", {
          hour: "numeric",
          minute: "2-digit",
        }),
        cpu: point.cpu,
        memory: point.memory,
        disk: point.disk,
        currentRate: point.ops,
        maxRate: point.rate_max,
        currentConnections: point.scur,
        currentLatency: point.latency,
        maxLatency: point.latency_max,
        hrsp_5xx: point.hrsp_5xx,
        hrsp_4xx: point.hrsp_4xx,
        errorPercent: point.error_percent,
      });
    });

    return result;
  };

  // Get data based on time range
  const getDataForTimeRange = (): ChartDataPoint[] => {
    if (!metricsData) return [];

    const timeRangeMap: Record<TimeRange, keyof HaproxyTimeSeriesResponse> = {
      MINUTE: "minute",
      HOUR: "hour",
      DAY: "day",
      WEEK: "week",
      MONTH: "month",
    };

    const dataKey = timeRangeMap[timeRange];
    const rawData = metricsData[dataKey] as MetricDataPoint[];
    return convertToChartData(rawData || [], timeRange);
  };

  const chartData = getDataForTimeRange();

  // Show loading state
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

  // Show error state
  if (error) {
    return (
      <Alert severity="error" sx={{ mb: 2 }}>
        {error}
      </Alert>
    );
  }

  // Show no data message if no real data available
  if (chartData.length === 0) {
    return (
      <Box
        display="flex"
        justifyContent="center"
        alignItems="center"
        height={400}
      >
        <Typography variant="body2" color="text.secondary">
          No metrics data available yet. Please wait for data collection to
          begin.
        </Typography>
      </Box>
    );
  }

  const displayData = chartData;

  // Define chart configurations - 4 charts in 2x2 grid
  const chartConfigs = [
    {
      title: "System Resources",
      metrics: [
        { key: "cpu", label: "CPU", unit: "%" },
        { key: "memory", label: "Memory", unit: "%" },
        { key: "disk", label: "Disk", unit: "%" },
      ],
      colors: [chartColors.primary, chartColors.warning, chartColors.success],
      type: "line" as const,
    },
    {
      title: "Operations/Concurrency",
      metrics: [
        { key: "currentRate", label: "Current Rate", unit: "req/s" },
        { key: "maxRate", label: "Max Rate", unit: "req/s" },
        {
          key: "currentConnections",
          label: "Current Connections",
          unit: "conn",
        },
      ],
      colors: [chartColors.primary, chartColors.info, chartColors.teal],
      type: "bar" as const,
    },
    {
      title: "Latency",
      metrics: [
        { key: "currentLatency", label: "Current", unit: "ms" },
        { key: "maxLatency", label: "Max", unit: "ms" },
      ],
      colors: [chartColors.warning, chartColors.error],
      type: "line" as const,
    },
    {
      title: "Health",
      metrics: [
        { key: "hrsp_5xx", label: "5xx Errors", unit: "count" },
        { key: "hrsp_4xx", label: "4xx Errors", unit: "count" },
        { key: "errorPercent", label: "Error %", unit: "%" },
      ],
      colors: [chartColors.error, chartColors.warning, chartColors.secondary],
      type: "bar" as const,
    },
  ];

  const formatValue = (value: number | null, unit: string): string => {
    if (value === null || isNaN(value)) {
      return "N/A";
    }

    switch (unit) {
      case "req/s":
        return value.toLocaleString();
      case "%":
        return value.toFixed(1) + "%";
      case "ms":
        return value.toFixed(1) + " ms";
      case "conn":
        return value.toLocaleString();
      case "count":
        return value.toLocaleString();
      default:
        return value.toString();
    }
  };

  const customTooltip = ({ active, payload, label }: any) => {
    if (!active || !payload || !payload.length) return null;

    return (
      <Paper sx={{ p: 1 }}>
        <Typography variant="subtitle2" gutterBottom>
          {label}
        </Typography>
        {payload.map((entry: any, index: number) => {
          const config = chartConfigs.find((c) =>
            c.metrics.some((m) => m.key === entry.dataKey)
          );
          const metric = config?.metrics.find((m) => m.key === entry.dataKey);
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
      {/* Charts Grid - 2x2 layout for 4 HAProxy metrics */}
      <Box
        display="grid"
        gridTemplateColumns="repeat(2, 1fr)"
        gap={2}
        sx={{ maxWidth: "100%" }}
      >
        {chartConfigs.map((config, index) => (
          <Paper key={index} sx={{ p: 1 }}>
            <Typography variant="subtitle2" gutterBottom>
              {config.title}
            </Typography>
            <ResponsiveContainer width="100%" height={200}>
              {config.type === "bar" ? (
                <BarChart
                  data={displayData}
                  margin={{ top: 5, right: 30, left: 20, bottom: 20 }}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="#e0e0e0" />
                  <XAxis
                    dataKey="time"
                    tick={{ fontSize: 12, fill: "currentColor" }}
                    axisLine={{ stroke: "#e0e0e0" }}
                    tickLine={{ stroke: "#e0e0e0" }}
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{ fontSize: 10, fill: "currentColor" }}
                    axisLine={{ stroke: "#e0e0e0" }}
                    tickLine={{ stroke: "#e0e0e0" }}
                    width={60}
                  />
                  <Tooltip content={customTooltip} />
                  <Legend
                    wrapperStyle={{ fontSize: "12px" }}
                    formatter={(value) => {
                      const metric = config.metrics.find(
                        (m) => m.key === value
                      );
                      return metric?.label || value;
                    }}
                  />
                  {config.metrics.map((metric, metricIndex) => (
                    <Bar
                      key={metric.key}
                      dataKey={metric.key}
                      fill={config.colors[metricIndex]}
                      name={metric.label}
                      isAnimationActive={false}
                    />
                  ))}
                </BarChart>
              ) : (
                <LineChart
                  data={displayData}
                  margin={{ top: 5, right: 30, left: 20, bottom: 20 }}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="#e0e0e0" />
                  <XAxis
                    dataKey="time"
                    tick={{ fontSize: 12, fill: "currentColor" }}
                    axisLine={{ stroke: "#e0e0e0" }}
                    tickLine={{ stroke: "#e0e0e0" }}
                    interval="preserveStartEnd"
                  />
                  <YAxis
                    tick={{ fontSize: 10, fill: "currentColor" }}
                    axisLine={{ stroke: "#e0e0e0" }}
                    tickLine={{ stroke: "#e0e0e0" }}
                    width={60}
                  />
                  <Tooltip content={customTooltip} />
                  <Legend
                    wrapperStyle={{ fontSize: "12px" }}
                    formatter={(value) => {
                      const metric = config.metrics.find(
                        (m) => m.key === value
                      );
                      return metric?.label || value;
                    }}
                  />
                  {config.metrics.map((metric, metricIndex) => (
                    <Line
                      key={metric.key}
                      type="monotone"
                      dataKey={metric.key}
                      stroke={config.colors[metricIndex]}
                      strokeWidth={2}
                      dot={false}
                      connectNulls={false}
                      name={metric.label}
                      isAnimationActive={false}
                    />
                  ))}
                </LineChart>
              )}
            </ResponsiveContainer>
          </Paper>
        ))}
      </Box>
    </Box>
  );
};

export default HAProxyMetricsCharts;
