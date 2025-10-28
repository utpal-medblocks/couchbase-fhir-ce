export interface HaproxyMetrics {
  summary: {
    totalRequests: number;
    totalSessions: number;
    activeServices: number;
    timestamp: number;
  };
  services: {
    [serviceName: string]: {
      status: string;
      stot: number; // total sessions
      req_tot: number; // total requests
      hrsp_2xx: number; // successful responses
      hrsp_3xx: number; // redirects
      hrsp_4xx: number; // client errors
      hrsp_5xx: number; // server errors
      scur: number; // current sessions
      smax: number; // max sessions
      rate: number; // current request rate
      [key: string]: any; // other HAProxy fields
    };
  };
}

export interface HaproxyMetricsResponse {
  haproxy: HaproxyMetrics;
  timestamp: number;
  error?: string;
}

export interface MetricDataPoint {
  timestamp: number;
  ops: number;
  scur: number;
  rate_max: number;
  smax: number;
  qcur: number;
  latency: number;
  latency_max: number;
  cpu: number;
  memory: number;
  disk: number;
  hrsp_4xx: number;
  hrsp_5xx: number;
  error_percent: number;
  // JVM extended fields
  heap_used_bytes?: number;
  heap_max_bytes?: number;
  metaspace_used_bytes?: number;
  metaspace_max_bytes?: number;
  direct_buffer_used_bytes?: number;
  direct_buffer_count?: number;
  mapped_buffer_used_bytes?: number;
  mapped_buffer_count?: number;
  gc_pause_count_delta?: number;
  gc_pause_time_ms_delta?: number;
  threads_live?: number;
  // Heap generations
  heap_young_used_bytes?: number;
  heap_old_used_bytes?: number;
  heap_total_used_bytes?: number;
}

export interface HaproxyTimeSeriesResponse {
  minute: MetricDataPoint[];
  hour: MetricDataPoint[];
  day: MetricDataPoint[];
  week: MetricDataPoint[];
  month: MetricDataPoint[];
  current: any;
  timestamp: number;
}

class HaproxyMetricsService {
  private baseUrl = "/api/dashboard";
  private timeSeriesUrl = "/api/metrics";

  async getHaproxyMetrics(): Promise<HaproxyMetrics> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    try {
      const response = await fetch(`${this.baseUrl}/haproxy-metrics`, {
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data: HaproxyMetricsResponse = await response.json();

      if (data.error) {
        throw new Error(data.error);
      }

      return data.haproxy;
    } catch (error) {
      clearTimeout(timeoutId);
      throw error;
    }
  }

  // Get simplified metrics for dashboard display
  async getDashboardMetrics(): Promise<{
    totalRequests: number;
    currentSessions: number;
    successRate: number;
    errorRate: number;
    frontendStatus: string;
    backendStatus: string;
  }> {
    const metrics = await this.getHaproxyMetrics();

    const frontendService = Object.values(metrics.services).find(
      (s) => s.svname === "FRONTEND"
    );
    const backendServices = Object.values(metrics.services).filter(
      (s) => s.svname === "BACKEND"
    );

    let totalRequests = metrics.summary.totalRequests;
    let total2xx = 0;
    let total4xx = 0;
    let total5xx = 0;

    // Aggregate response codes from all services
    Object.values(metrics.services).forEach((service) => {
      total2xx += service.hrsp_2xx || 0;
      total4xx += service.hrsp_4xx || 0;
      total5xx += service.hrsp_5xx || 0;
    });

    const totalResponses = total2xx + total4xx + total5xx;
    const successRate =
      totalResponses > 0 ? (total2xx / totalResponses) * 100 : 100;
    const errorRate =
      totalResponses > 0 ? ((total4xx + total5xx) / totalResponses) * 100 : 0;

    return {
      totalRequests,
      currentSessions: metrics.summary.totalSessions,
      successRate,
      errorRate,
      frontendStatus: frontendService?.status || "UNKNOWN",
      backendStatus:
        backendServices.length > 0
          ? backendServices.every((s) => s.status === "UP")
            ? "UP"
            : "DOWN"
          : "UNKNOWN",
    };
  }

  // Get time-series metrics for dashboard charts
  async getTimeSeriesMetrics(): Promise<HaproxyTimeSeriesResponse> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    try {
      const response = await fetch(`${this.timeSeriesUrl}/haproxy-timeseries`, {
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data: HaproxyTimeSeriesResponse = await response.json();
      return data;
    } catch (error) {
      clearTimeout(timeoutId);
      throw error;
    }
  }
}

export const haproxyMetricsService = new HaproxyMetricsService();
