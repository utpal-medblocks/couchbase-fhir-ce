import { useState, useEffect, useCallback } from "react";
import {
  fhirMetricsService,
  type FhirMetrics,
} from "../services/fhirMetricsService";

interface UseFhirMetricsReturn {
  metrics: FhirMetrics | null;
  loading: boolean;
  error: string | null;
  refreshMetrics: () => Promise<void>;
}

export const useFhirMetrics = (): UseFhirMetricsReturn => {
  const [metrics, setMetrics] = useState<FhirMetrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchMetrics = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const data = await fhirMetricsService.getFhirMetrics();
      setMetrics(data);
    } catch (err: any) {
      if (err.name === "AbortError") {
        setError("Request timed out - backend may not be responding");
      } else if (err.message.includes("404")) {
        setError("FHIR metrics endpoint not available");
      } else {
        setError("Failed to fetch FHIR metrics");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshMetrics = useCallback(async () => {
    await fetchMetrics();
  }, [fetchMetrics]);

  useEffect(() => {
    fetchMetrics();

    // Refresh every 30 seconds
    const interval = setInterval(fetchMetrics, 30000);
    return () => clearInterval(interval);
  }, [fetchMetrics]);

  return {
    metrics,
    loading,
    error,
    refreshMetrics,
  };
};
