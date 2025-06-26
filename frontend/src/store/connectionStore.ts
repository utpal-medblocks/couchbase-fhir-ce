import { create } from "zustand";
import axios from "axios";
import { connectionService } from "../services/connectionService";

// Types for connection details
interface ConnectionInfo {
  id: string;
  name: string;
  version: string;
  isConnected: boolean;
}

interface ConnectionRequest {
  connectionString: string;
  username: string;
  password: string;
  serverType: "Server" | "Capella";
  sslEnabled: boolean;
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
  showDialog: boolean;

  // Metrics state
  metrics: ClusterMetrics | null;
  metricsError: string | null;
  //  fhirBuckets: Set<string>;

  // Connection actions
  setConnection: (connection: ConnectionInfo) => void;
  setError: (error: string | null) => void;
  setShowDialog: (show: boolean) => void;

  // Metrics actions
  setMetrics: (metrics: ClusterMetrics | null) => void;
  setMetricsError: (error: string | null) => void;
  // setFhirBuckets: (buckets: Set<string>) => void;
  // toggleFhirBucket: (bucketName: string) => void;

  // Async actions
  fetchConnection: () => Promise<void>;
  createConnection: (
    request: ConnectionRequest
  ) => Promise<{ success: boolean; error?: string }>;
  deleteConnection: () => Promise<void>;
  fetchMetrics: () => Promise<void>;
  //  fetchFhirBuckets: () => Promise<void>;

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
  showDialog: false,
  metrics: null,
  metricsError: null,
  fhirBuckets: new Set<string>(),
};

export const useConnectionStore = create<ConnectionState>((set, get) => ({
  // Initial state
  ...initialState,

  // Synchronous actions
  setConnection: (connection) => {
    //    console.log("🔍 connectionStore: Setting connection", connection);
    set({ connection });
  },

  setError: (error) => {
    //    console.log("🔍 connectionStore: Setting error", error);
    set({ error });
  },

  setShowDialog: (showDialog) => {
    //    console.log("🔍 connectionStore: Setting showDialog", showDialog);
    set({ showDialog });
  },

  clearError: () => set({ error: null }),

  reset: () => set(initialState),

  // Metrics actions
  setMetrics: (metrics) => {
    //    console.log("connectionStore: Setting metrics", metrics);
    if (metrics && metrics.buckets) {
      console.log(
        "🔍 connectionStore: Bucket metrics with FHIR status:",
        metrics.buckets.map((bucket) => ({
          name: bucket.name,
          isFhirBucket: bucket.isFhirBucket,
        }))
      );
    }
    set({ metrics });
  },

  setMetricsError: (error) => {
    //    console.log("🔍 connectionStore: Setting metricsError", error);
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

  // setFhirBuckets: (buckets) => {
  //   //    console.log("🔍 connectionStore: Setting fhirBuckets", buckets);
  //   set({ fhirBuckets: buckets });
  // },

  // toggleFhirBucket: (bucketName) => {
  //   //    console.log("🔍 connectionStore: Toggling fhirBucket", bucketName);
  //   const { fhirBuckets } = get();
  //   const newBuckets = new Set(fhirBuckets);
  //   if (fhirBuckets.has(bucketName)) {
  //     newBuckets.delete(bucketName);
  //   } else {
  //     newBuckets.add(bucketName);
  //   }
  //   set({ fhirBuckets: newBuckets });
  // },

  // Async actions
  fetchConnection: async () => {
    //    console.log("🔗 connectionStore: Starting fetchConnection");
    set({ error: null });

    try {
      const response = await axios.get("/api/connections/active", {
        timeout: 5000,
      });

      //      console.log("🔍 connectionStore: API response", response.data);

      if (response.data && response.data.success && response.data.connections) {
        const connections = response.data.connections;
        // console.log(
        //   "🔍 connectionStore: API returned connections",
        //   connections
        // );

        if (connections.length > 0) {
          const activeConnection = connections[0];
          const connectionInfo: ConnectionInfo = {
            id: `conn-${activeConnection}`,
            name: activeConnection,
            version: "7.6.0",
            isConnected: true,
          };

          // console.log(
          //   "🔍 connectionStore: Setting connection info",
          //   connectionInfo
          // );
          set({ connection: connectionInfo });
        } else {
          //          console.log("🔍 connectionStore: No connections available");
          set({
            connection: {
              id: "No Connection",
              name: "No Connection",
              version: "No Connection",
              isConnected: false,
            },
          });
        }
      } else {
        // console.log(
        //   "🔍 connectionStore: Invalid response format",
        //   response.data
        // );
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
      //      console.error("❌ connectionStore: fetchConnection failed", error);
      let errorMessage = "Failed to fetch connection";

      if (error.name === "AbortError" || error.code === "ECONNABORTED") {
        errorMessage = "Connection request timed out";
      } else if (error.response?.status === 404) {
        errorMessage = "Backend not available";
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
    }
  },

  createConnection: async (connectionRequest: ConnectionRequest) => {
    //    console.log("🔗 connectionStore: Creating connection", connectionRequest);
    set({ error: null });

    try {
      const safeConnectionString = connectionRequest.connectionString.replace(
        /localhost(?![\w.])/g,
        "127.0.0.1"
      );

      const connectionName = `${
        connectionRequest.serverType || "Couchbase"
      }-${Date.now()}`;

      const safeRequest = {
        ...connectionRequest,
        name: connectionName,
        connectionString: safeConnectionString,
      };

      const createResponse = await axios.post(
        "/api/connections/create",
        safeRequest
      );

      if (!createResponse.data.success) {
        const errorMessage =
          createResponse.data.error || "Failed to create connection";
        set({ error: errorMessage });
        return { success: false, error: errorMessage };
      }

      // Store connection details for metrics
      try {
        await axios.post("/api/metrics/connection-details", {
          name: connectionName,
          connectionString: safeRequest.connectionString,
          username: safeRequest.username,
          password: safeRequest.password,
        });
      } catch (metricsError) {
        // console.warn(
        //   "[connectionStore] Failed to store connection details for metrics:",
        //   metricsError
        // );
      }

      // Update connection state
      const connectionInfo: ConnectionInfo = {
        id: `conn-${connectionName}`,
        name: connectionName,
        version: "7.6.0",
        isConnected: true,
      };

      // console.log(
      //   "🔍 connectionStore: Setting connection after creation",
      //   connectionInfo
      // );
      set({
        connection: connectionInfo,
        showDialog: false,
      });

      // Fetch metrics immediately after successful connection
      setTimeout(() => {
        get().fetchMetrics();
      }, 100);

      return { success: true };
    } catch (error: any) {
      console.error("[connectionStore] Error creating connection:", error);
      const errorMessage =
        error.response?.data?.error || "Failed to create connection";
      set({ error: errorMessage });
      return { success: false, error: errorMessage };
    }
  },

  deleteConnection: async () => {
    const { connection } = get();
    if (!connection.isConnected) {
      return;
    }

    //    console.log("🔗 connectionStore: Deleting connection", connection.name);

    try {
      await axios.delete(`/api/connections/${connection.name}`);
      await axios.delete(`/api/metrics/connection-details/${connection.name}`);

      set({
        connection: {
          id: "No Connection",
          name: "No Connection",
          version: "No Connection",
          isConnected: false,
        },
        showDialog: true,
        metrics: null,
      });
    } catch (error: any) {
      console.error("[connectionStore] Error deleting connection:", error);
      set({
        error: "Failed to delete connection",
      });
    }
  },

  fetchMetrics: async () => {
    const { connection } = get();
    // console.log(
    //   "🔗 connectionStore: Starting fetchMetrics for connection:",
    //   connection.name
    // );

    if (!connection.isConnected || !connection.name) {
      //      console.log("🔍 connectionStore: No connection, skipping metrics fetch");
      set({ metrics: null, metricsError: null });
      return;
    }

    set({ metricsError: null });

    try {
      const metricsData = await connectionService.getClusterMetrics(
        connection.name
      );

      console.log(
        "🔍 connectionStore: Raw metrics data from API:",
        metricsData
      );
      console.log(
        "🔍 connectionStore: Buckets in raw data:",
        metricsData.buckets?.map((bucket: any) => ({
          name: bucket.name,
          isFhirBucket: bucket.isFhirBucket,
          hasIsFhirBucket: "isFhirBucket" in bucket,
        }))
      );

      //      console.log("🔍 connectionStore: Received metrics data:", metricsData);

      // Always update with new data using the setMetrics action
      get().setMetrics({
        ...metricsData,
        retrievedAt: Date.now(),
      });
    } catch (error: any) {
      //      console.error("❌ connectionStore: fetchMetrics failed", error);
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

  // fetchFhirBuckets: async () => {
  //   const { connection } = get();

  //   if (!connection.isConnected || !connection.name) {
  //     set({ fhirBuckets: new Set<string>() });
  //     return;
  //   }

  //   try {
  //     const response = await axios.get(
  //       `/api/admin/fhir-buckets/fhir-buckets?connectionName=${connection.name}`
  //     );

  //     if (response.data && response.data.fhirBuckets) {
  //       const fhirBucketsSet = new Set<string>(response.data.fhirBuckets);
  //       set({ fhirBuckets: fhirBucketsSet });
  //     } else {
  //       set({ fhirBuckets: new Set<string>() });
  //     }
  //   } catch (error: any) {
  //     console.warn("Failed to fetch FHIR buckets:", error);
  //     // Don't set error state for this, just use empty set
  //     set({ fhirBuckets: new Set<string>() });
  //   }
  // },
}));
