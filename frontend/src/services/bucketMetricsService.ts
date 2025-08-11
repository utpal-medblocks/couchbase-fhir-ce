// Bucket-level metrics service for KV operations, cache ratios, query times, N1QL rates

export interface BucketMetricsResponse {
  metrics: BucketMetricData[];
  timestamps: number[];
}

export interface BucketMetricData {
  name: string;
  label: string;
  unit: string;
  dataPoints: BucketMetricDataPoint[];
}

export interface BucketMetricDataPoint {
  timestamp: number;
  value: number | null; // null for NaN values
}

export type TimeRange = "MINUTE" | "HOUR" | "DAY" | "WEEK" | "MONTH";

export const TIME_RANGE_OPTIONS = [
  { value: "MINUTE" as TimeRange, label: "Last Minute" },
  { value: "HOUR" as TimeRange, label: "Last Hour" },
  { value: "DAY" as TimeRange, label: "Last Day" },
  { value: "WEEK" as TimeRange, label: "Last Week" },
  { value: "MONTH" as TimeRange, label: "Last Month" },
];

export const getBucketMetrics = async (
  connectionName: string,
  bucketName: string,
  timeRange: TimeRange
): Promise<BucketMetricsResponse> => {
  const params = new URLSearchParams({
    connectionName,
    bucketName,
    timeRange,
  });

  const response = await fetch(`/api/buckets/metrics?${params.toString()}`);

  if (!response.ok) {
    throw new Error(`Failed to fetch bucket metrics: ${response.statusText}`);
  }

  return response.json();
};
