import { create } from "zustand";
import axios from "axios";

// Types for connection details
interface ConnectionInfo {
  id: string;
  name: string;
  version: string;
  isConnected: boolean;
  isSSL?: boolean; // SSL connection status
}

// Types for metrics data
interface NodeMetrics {
  hostname: string;
  status: string;
  cpu: number;
  ram: number;
  cpuUtilizationRate: number;
  ramUtilizationRate: number;
  diskUtilizationRate: number;
  services: string[];
  version: string;
}

interface BucketMetrics {
  name: string;
  ramQuota: number;
  ramUsed: number;
  itemCount: number;
  diskUsed: number;
  opsPerSec: number;
  diskFetches: number;
  residentRatio: number;
  quotaPercentUsed: number;
  dataUsed: number;
  vbActiveNumNonResident: number;
  isFhirBucket?: boolean;
  status?: string; // "Ready" or "Building"
}

interface ClusterAlert {
  msg: string;
  serverTime: string;
  disableUIPopUp: boolean;
}

interface ServiceQuotas {
  memoryQuota: number;
  queryMemoryQuota: number;
  indexMemoryQuota: number;
  ftsMemoryQuota: number;
  cbasMemoryQuota: number;
  eventingMemoryQuota: number;
}

interface StorageTotals {
  ram: {
    total: number;
    quotaTotal: number;
    quotaUsed: number;
    used: number;
    usedByData: number;
    quotaUsedPerNode: number;
    quotaTotalPerNode: number;
  };
  hdd: {
    total: number;
    quotaTotal: number;
    used: number;
    usedByData: number;
    free: number;
  };
}

interface ClusterMetrics {
  nodes: NodeMetrics[];
  buckets: BucketMetrics[];
  clusterName: string;
  alerts: ClusterAlert[];
  storageTotals?: StorageTotals;
  serviceQuotas?: ServiceQuotas;
  retrievedAt?: number;
}

interface ConnectionState {
  // Connection state
  connection: ConnectionInfo;
  error: string | null;
  backendReady: boolean; // Track if backend has ever responded

  // Metrics state
  metrics: ClusterMetrics | null;
  metricsError: string | null;

  // Connection actions
  setConnection: (connection: ConnectionInfo) => void;
  setError: (error: string | null) => void;

  // Metrics actions
  setMetrics: (metrics: ClusterMetrics | null) => void;
  setMetricsError: (error: string | null) => void;

  // Async actions
  fetchConnection: () => Promise<void>;
  fetchMetrics: () => Promise<void>;

  // Utility actions
  clearError: () => void;
  reset: () => void;

  // Update a bucket's FHIR status
  setBucketFhirStatus: (bucketName: string, isFhir: boolean) => void;
}

const initialState = {
  connection: {
    id: "No Connection",
    name: "No Connection",
    version: "No Connection",
    isConnected: false,
  },
  error: null,
  backendReady: false,
  metrics: null,
  metricsError: null,
  fhirBuckets: new Set<string>(),
};

export const useConnectionStore = create<ConnectionState>((set, get) => ({
  // Initial state
  ...initialState,

  // Synchronous actions
  setConnection: (connection) => {
    set({ connection });
  },

  setError: (error) => {
    set({ error });
  },

  clearError: () => set({ error: null }),

  reset: () => set(initialState),

  // Metrics actions
  setMetrics: (metrics) => {
    if (metrics && metrics.buckets) {
      // Additional processing if needed
    }
    set({ metrics });
  },

  setMetricsError: (error) => {
    set({ metricsError: error });
  },

  // Update a bucket's FHIR status
  setBucketFhirStatus: (bucketName: string, isFhir: boolean) => {
    const { metrics } = get();
    if (metrics && metrics.buckets) {
      const updatedBuckets = metrics.buckets.map((bucket) =>
        bucket.name === bucketName
          ? { ...bucket, isFhirBucket: isFhir }
          : bucket
      );
      set({
        metrics: {
          ...metrics,
          buckets: updatedBuckets,
        },
      });
    }
  },

  // Async actions
  fetchConnection: async () => {
    try {
      const response = await axios.get("/api/connections/active", {
        timeout: 5000,
      });

      console.log("🔍 connectionStore: API response", response.data);

      // Backend responded - mark as ready
      set({ backendReady: true });

      if (response.data && response.data.success) {
        // Check for lastConnectionError from backend
        if (response.data.lastConnectionError) {
          console.log(
            "🔍 connectionStore: Backend reports connection error:",
            response.data.lastConnectionError
          );
          set({ error: response.data.lastConnectionError });
        } else {
          set({ error: null });
        }

        if (response.data.connections) {
          const connections = response.data.connections;
          console.log(
            "🔍 connectionStore: API returned connections",
            connections
          );

          if (connections.length > 0) {
            const activeConnection = connections[0];

            // TODO: Backend should return complete ConnectionInfo including SSL status
            const connectionInfo: ConnectionInfo = {
              id: `conn-${activeConnection}`,
              name: activeConnection,
              version: "7.6.0", // TODO: Get from backend response
              isConnected: true,
              isSSL: false, // TODO: Backend should provide this
            };

            console.log(
              "✅ connectionStore: Setting connection info",
              connectionInfo
            );
            set({ connection: connectionInfo });

            // Fetch cluster metrics immediately after connection success
            setTimeout(() => {
              console.log(
                "🔍 connectionStore: Triggering fetchMetrics after connection"
              );
              get().fetchMetrics();
            }, 100);
          } else {
            set({
              connection: {
                id: "No Connection",
                name: "No Connection",
                version: "No Connection",
                isConnected: false,
              },
            });
          }
        }
      } else {
        console.log(
          "🔍 connectionStore: Invalid response format",
          response.data
        );
        set({
          connection: {
            id: "No Connection",
            name: "No Connection",
            version: "No Connection",
            isConnected: false,
          },
        });
      }
    } catch (error: any) {
      console.log("🔗 connectionStore: fetchConnection failed", error.message);

      const { backendReady } = get();

      // Only show errors if backend was previously ready (real connection issues)
      // Don't show errors during initial startup when backend isn't ready yet
      if (backendReady) {
        let errorMessage = "Connection lost - check backend status";

        if (error.response?.status === 401) {
          errorMessage =
            "Authentication failed - check credentials in config.yaml";
        } else if (error.response?.data?.error) {
          errorMessage = error.response.data.error;
        }

        set({
          error: errorMessage,
          connection: {
            id: "No Connection",
            name: "No Connection",
            version: "No Connection",
            isConnected: false,
          },
        });
      } else {
        // Backend not ready yet - just set connection to disconnected, no error
        set({
          connection: {
            id: "No Connection",
            name: "No Connection",
            version: "No Connection",
            isConnected: false,
          },
        });
      }
    }
  },

  fetchMetrics: async () => {
    const { connection } = get();

    if (!connection.isConnected || !connection.name) {
      set({ metrics: null, metricsError: null });
      return;
    }

    set({ metricsError: null });

    try {
      const response = await axios.get(`/api/dashboard/metrics`, {
        params: { connectionName: connection.name },
        timeout: 5000,
      });
      const metricsData = response.data;

      console.log(
        "🔍 connectionStore: Received Couchbase cluster metrics:",
        metricsData
      );

      // Always update with new data using the setMetrics action
      get().setMetrics({
        ...metricsData,
        retrievedAt: Date.now(),
      });
    } catch (error: any) {
      let errorMessage = "Failed to fetch metrics";

      if (error.name === "AbortError" || error.code === "ECONNABORTED") {
        errorMessage = "Metrics request timed out";
      } else if (error.response?.status === 404) {
        errorMessage = "Backend not available";
      } else if (error.response?.data?.error) {
        errorMessage = error.response.data.error;
      }

      set({
        metrics: null,
        metricsError: errorMessage,
      });
    }
  },
}));
