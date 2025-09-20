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

  // Format timestamp based on time range (like BucketMetricsCharts)
  const formatTimestamp = (timestamp: number, timeRange: TimeRange): string => {
    const date = new Date(timestamp);

    switch (timeRange) {
      case "MINUTE":
        // Format: 4:58:30 PM (show seconds for minute view)
        return date.toLocaleTimeString("en-US", {
          hour12: true,
          hour: "numeric",
          minute: "2-digit",
          second: "2-digit",
        });
      case "HOUR":
        // Format: 4:30 PM (show minutes for hour view)
        return date.toLocaleTimeString("en-US", {
          hour12: true,
          hour: "numeric",
          minute: "2-digit",
        });
      case "DAY":
        // Format: Aug 7, 12 PM (show hours for day view)
        return date.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
          hour: "numeric",
          hour12: true,
        });
      case "WEEK":
        // Format: Aug 1 (show days for week view)
        return date.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
        });
      case "MONTH":
        // Format: Jul 13 (show days for month view)
        return date.toLocaleDateString("en-US", {
          month: "short",
          day: "numeric",
        });
      default:
        return date.toLocaleTimeString();
    }
  };

  // Calculate XAxis tick interval (like BucketMetricsCharts)
  const calculateTickInterval = (dataLength: number): number => {
    let maxTicks: number;

    switch (timeRange) {
      case "MINUTE":
        maxTicks = 3;
        break;
      case "HOUR":
      case "DAY":
      case "WEEK":
      case "MONTH":
        maxTicks = 3;
        break;
      default:
        maxTicks = 3;
    }

    const interval = Math.max(1, Math.floor(dataLength / maxTicks));
    return interval;
  };

  // Build a uniformly spaced time-series of targetCount points.
  // Any missing real samples are represented with null metric values so recharts can gap / connect accordingly.
  // Strategy:
  // 1. Sort incoming points ascending by timestamp.
  // 2. Snap each point to its interval bucket (floor) so slight drift does not cause mis-alignment.
  // 3. Determine the most recent bucket (last point) and then derive the start bucket = end - (count-1)*interval.
  // 4. Iterate from start..end building ChartDataPoint entries, filling with real values where present, else nulls.
  const convertToChartData = (
    rawPoints: MetricDataPoint[],
    range: TimeRange
  ): ChartDataPoint[] => {
    const targetCounts: Record<TimeRange, number> = {
      MINUTE: 10, // 6s resolution
      HOUR: 60, // 1m resolution
      DAY: 60, // 24m resolution
      WEEK: 60, // 168m (2h48m) resolution
      MONTH: 60, // 12h resolution (30d window assumption)
    };
    const intervalsMs: Record<TimeRange, number> = {
      MINUTE: 6_000,
      HOUR: 60_000,
      DAY: 1_440_000,
      WEEK: 10_080_000,
      MONTH: 43_200_000,
    };

    const targetCount = targetCounts[range];
    const interval = intervalsMs[range];

    if (!rawPoints || rawPoints.length === 0) return [];

    // 1. Sort ascending
    const points = [...rawPoints].sort((a, b) => a.timestamp - b.timestamp);

    // 2. Bucket map (snap down to nearest interval)
    const bucketMap = new Map<number, MetricDataPoint>();
    points.forEach((p) => {
      const bucketTs = Math.floor(p.timestamp / interval) * interval;
      bucketMap.set(bucketTs, p); // latest sample for bucket wins
    });

    // 3. Determine end bucket (last point snapped) and start
    const endBucket =
      Math.floor(points[points.length - 1].timestamp / interval) * interval;
    const startBucket = endBucket - (targetCount - 1) * interval;

    // 4. Build series
    const series: ChartDataPoint[] = [];
    for (let ts = startBucket; ts <= endBucket; ts += interval) {
      const real = bucketMap.get(ts);
      const base: ChartDataPoint = {
        timestamp: ts,
        time: formatTimestamp(ts, range),
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
      };

      if (real) {
        base.cpu = real.cpu;
        base.memory = real.memory;
        base.disk = real.disk;
        base.currentRate = real.ops;
        base.maxRate = real.rate_max;
        base.currentConnections = real.scur;
        base.currentLatency = real.latency;
        base.maxLatency = real.latency_max;
        base.hrsp_5xx = real.hrsp_5xx;
        base.hrsp_4xx = real.hrsp_4xx;
        base.errorPercent = real.error_percent;
      }

      series.push(base);
    }

    return series;
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
    const rawData = (metricsData as any)[dataKey] as
      | MetricDataPoint[]
      | undefined;
    if (rawData && rawData.length > 0) {
      return convertToChartData(rawData, timeRange);
    }

    // Fallback: if requested higher range has no data yet, synthesize a skeleton using the latest available lower range
    const fallbackPriority: TimeRange[] = ["HOUR", "MINUTE"]; // order to check for seed data
    for (const fallbackRange of fallbackPriority) {
      if (fallbackRange === timeRange) continue;
      const fk = timeRangeMap[fallbackRange];
      const seed = (metricsData as any)[fk] as MetricDataPoint[] | undefined;
      if (seed && seed.length) {
        // Use last point timestamp from seed to anchor an empty series for target range
        const targetCounts: Record<TimeRange, number> = {
          MINUTE: 10,
          HOUR: 60,
          DAY: 60,
          WEEK: 60,
          MONTH: 60,
        };
        const intervalsMs: Record<TimeRange, number> = {
          MINUTE: 6_000,
          HOUR: 60_000,
          DAY: 1_440_000,
          WEEK: 10_080_000,
          MONTH: 43_200_000,
        };
        const count = targetCounts[timeRange];
        const interval = intervalsMs[timeRange];
        const lastTs = seed[seed.length - 1].timestamp;
        const endBucket = Math.floor(lastTs / interval) * interval;
        const startBucket = endBucket - (count - 1) * interval;
        const skeleton: ChartDataPoint[] = [];
        for (let ts = startBucket; ts <= endBucket; ts += interval) {
          skeleton.push({
            timestamp: ts,
            time: formatTimestamp(ts, timeRange),
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
        return skeleton;
      }
    }

    return [];
  };

  const chartData = getDataForTimeRange();

  // DEBUG: Log raw incoming buffers & derived series to diagnose bogus values / XAxis issues
  useEffect(() => {
    if (!metricsData) return;
    try {
      const timeRangeMap: Record<TimeRange, keyof HaproxyTimeSeriesResponse> = {
        MINUTE: "minute",
        HOUR: "hour",
        DAY: "day",
        WEEK: "week",
        MONTH: "month",
      };
      const key = timeRangeMap[timeRange];
      const raw = (metricsData as any)[key] as MetricDataPoint[] | undefined;
      if (!raw) return;

      const tsList = raw.map((r) => r.timestamp).sort((a, b) => a - b);
      const first = tsList[0];
      const last = tsList[tsList.length - 1];
      const deltas = tsList.slice(1).map((t, i) => t - tsList[i]);
      const deltaStats = deltas.length
        ? {
            count: deltas.length,
            min: Math.min(...deltas),
            max: Math.max(...deltas),
            avg: Math.round(deltas.reduce((s, v) => s + v, 0) / deltas.length),
          }
        : null;

      // Light-weight signature of metric value ranges to spot corruption
      const summarizeField = (field: keyof MetricDataPoint) => {
        const vals = raw
          .map((r) => r[field])
          .filter((v) => typeof v === "number") as number[];
        if (!vals.length) return null;
        return {
          min: Math.min(...vals),
          max: Math.max(...vals),
          avg: Number(
            (vals.reduce((s, v) => s + v, 0) / vals.length).toFixed(2)
          ),
        };
      };

      const fieldSummary: Record<string, any> = {
        cpu: summarizeField("cpu"),
        memory: summarizeField("memory"),
        disk: summarizeField("disk"),
        ops: summarizeField("ops"),
        rate_max: summarizeField("rate_max"),
        scur: summarizeField("scur"),
        latency: summarizeField("latency"),
        latency_max: summarizeField("latency_max"),
        hrsp_4xx: summarizeField("hrsp_4xx"),
        hrsp_5xx: summarizeField("hrsp_5xx"),
        error_percent: summarizeField("error_percent"),
      };

      // Derived chart timestamps for comparison
      const derivedTs = chartData.map((p) => p.timestamp);
      const derivedDeltas = derivedTs.slice(1).map((t, i) => t - derivedTs[i]);
      const derivedStats = derivedDeltas.length
        ? {
            count: derivedDeltas.length,
            min: Math.min(...derivedDeltas),
            max: Math.max(...derivedDeltas),
            avg: Math.round(
              derivedDeltas.reduce((s, v) => s + v, 0) / derivedDeltas.length
            ),
          }
        : null;

      // Only log when something changes materially (length or last ts)
      console.debug("[HAProxyMetricsCharts][RAW]", {
        timeRange,
        rawCount: raw.length,
        firstTs: first,
        lastTs: last,
        deltaStats,
        fieldSummary,
      });
      console.debug("[HAProxyMetricsCharts][DERIVED]", {
        derivedCount: chartData.length,
        firstDerived: derivedTs[0],
        lastDerived: derivedTs[derivedTs.length - 1],
        derivedStats,
      });
      // Optional: diff bucket alignment mismatches
      if (raw.length && chartData.length) {
        const rawSet = new Set(tsList);
        const missingRaw = derivedTs.filter((ts) => !rawSet.has(ts));
        if (missingRaw.length) {
          console.debug(
            "[HAProxyMetricsCharts][GAPS] Derived buckets without raw sample",
            missingRaw.slice(0, 10),
            missingRaw.length > 10 ? "..." : ""
          );
        }
      }
    } catch (e) {
      console.warn("[HAProxyMetricsCharts][DEBUG_LOG_ERROR]", e);
    }
  }, [metricsData, chartData, timeRange]);

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

  // Define chart configurations - 4 charts in 2x2 grid (all line charts now)
  const chartConfigs = [
    {
      title: "System Resources",
      metrics: [
        { key: "cpu", label: "CPU", unit: "%" },
        { key: "memory", label: "Memory", unit: "%" },
        { key: "disk", label: "Disk", unit: "%" },
      ],
      colors: [chartColors.primary, chartColors.warning, chartColors.success],
    },
    {
      title: "Operations/Concurrency",
      metrics: [
        { key: "currentRate", label: "Current Rate", unit: "req/s" },
        {
          key: "currentConnections",
          label: "Current Connections",
          unit: "conn",
        },
      ],
      colors: [chartColors.primary, chartColors.teal],
    },
    {
      title: "Latency",
      metrics: [{ key: "currentLatency", label: "Current", unit: "ms" }],
      colors: [chartColors.warning],
    },
    {
      title: "Health",
      metrics: [
        { key: "hrsp_5xx", label: "5xx Errors", unit: "count" },
        { key: "hrsp_4xx", label: "4xx Errors", unit: "count" },
        { key: "errorPercent", label: "Error %", unit: "%" },
      ],
      colors: [chartColors.error, chartColors.warning, chartColors.secondary],
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
                  interval={calculateTickInterval(displayData.length)}
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
                    const metric = config.metrics.find((m) => m.key === value);
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
            </ResponsiveContainer>
          </Paper>
        ))}
      </Box>
    </Box>
  );
};

export default HAProxyMetricsCharts;
