// FTS Metrics Service for time-series data
export interface FtsMetricsDataPoint {
  timestamp: number;
  value: number | null;
}

export interface FtsMetricData {
  name: string;
  label: string;
  values: FtsMetricsDataPoint[];
  unit: string;
}

export interface FtsMetricsResponse {
  data: FtsMetricData[];
  timeRange: string;
  bucketName: string;
  indexName: string;
}

export type TimeRange = "MINUTE" | "HOUR" | "DAY" | "WEEK" | "MONTH";

export const TIME_RANGE_OPTIONS = [
  { value: "MINUTE", label: "Last Minute" },
  { value: "HOUR", label: "Last Hour" },
  { value: "DAY", label: "Last Day" },
  { value: "WEEK", label: "Last Week" },
  { value: "MONTH", label: "Last Month" },
] as const;

export const getFtsMetrics = async (
  connectionName: string,
  bucketName: string,
  indexName: string,
  timeRange: TimeRange = "HOUR"
): Promise<FtsMetricsResponse> => {
  const params = new URLSearchParams({
    connectionName,
    bucketName,
    indexName,
    timeRange,
  });

  const response = await fetch(`/api/fts/metrics?${params}`);

  if (!response.ok) {
    throw new Error(`Failed to fetch FTS metrics: ${response.statusText}`);
  }

  return response.json();
};
