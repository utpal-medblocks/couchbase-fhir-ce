import { create } from "zustand";
import {
  fhirMetricsService,
  type FhirMetrics,
} from "../services/fhirMetricsService";

interface FhirState {
  metrics: FhirMetrics | null;
  error: string | null;
  retrievedAt: Date | null;

  // Actions
  fetchMetrics: () => Promise<void>;
  refreshMetrics: () => Promise<void>;
  clearError: () => void;
}

export const useFhirStore = create<FhirState>((set, get) => {
  let intervalId: number | null = null;

  const startPolling = () => {
    if (intervalId) {
      clearInterval(intervalId);
    }
    // Refresh every 30 seconds
    intervalId = setInterval(() => {
      get().fetchMetrics();
    }, 30000);
  };

  const stopPolling = () => {
    if (intervalId) {
      clearInterval(intervalId);
      intervalId = null;
    }
  };

  return {
    metrics: null,
    error: null,
    retrievedAt: null,

    fetchMetrics: async () => {
      try {
        // console.log("🔍 fhirStore: Fetching FHIR metrics...");
        const data = await fhirMetricsService.getFhirMetrics();
        // console.log("✅ fhirStore: FHIR metrics received:", data);
        set((state) => {
          // Only update if data has actually changed
          const hasChanged =
            !state.metrics ||
            JSON.stringify(state.metrics) !== JSON.stringify(data);

          if (hasChanged) {
            return {
              metrics: data,
              error: null,
              retrievedAt: new Date(),
            };
          }
          return { retrievedAt: new Date() }; // Update timestamp only
        });

        // Start polling after first successful fetch
        if (!intervalId) {
          startPolling();
        }
      } catch (err: any) {
        let errorMessage = "Failed to fetch FHIR metrics";

        if (err.name === "AbortError") {
          errorMessage = "Request timed out - backend may not be responding";
        } else if (err.message.includes("404")) {
          errorMessage = "FHIR metrics endpoint not available";
        }

        // console.error("FHIR metrics fetch error:", err);
        set({ error: errorMessage });

        // Stop polling on error
        stopPolling();
      }
    },

    refreshMetrics: async () => {
      await get().fetchMetrics();
    },

    clearError: () => {
      set({ error: null });
    },
  };
});

// Cleanup function for when the store is no longer needed
export const cleanupFhirStore = () => {
  // Reset the store state
  useFhirStore.setState({ metrics: null, error: null, retrievedAt: null });
};
