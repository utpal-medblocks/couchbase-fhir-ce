import { create } from "zustand";

// FTS Index Types matching backend models
export interface FtsIndex {
  name: string;
  uuid: string;
  type: string;
  params: any;
  sourceType: string;
  sourceName: string;
  sourceUUID: string;
  planParams: any;
}

export interface FtsIndexDetails {
  indexName: string;
  status: string;
  docsIndexed: number;
  lastTimeUsed: string;
  queryLatency: number;
  queryRate: number;
  totalQueries: number;
  diskSize: number;
  indexDefinition: FtsIndex;
  bucketName: string;
  scopeName: string;
  avgQueryLatency: number;
  numFilesOnDisk: number;
  totalQueriesError: number;
  totalQueriesTimeout: number;
}

interface FtsIndexState {
  indexes: FtsIndexDetails[] | null;
  error: string | null;
  retrievedAt: Date | null;
  loading: boolean;

  // Actions
  fetchIndexes: (
    connectionName: string,
    bucketName: string,
    scopeName: string
  ) => Promise<void>;
  refreshIndexes: (
    connectionName: string,
    bucketName: string,
    scopeName: string
  ) => Promise<void>;
  clearError: () => void;
  clearIndexes: () => void;
}

export const useFtsIndexStore = create<FtsIndexState>((set, get) => {
  let intervalId: number | null = null;

  const startPolling = (
    connectionName: string,
    bucketName: string,
    scopeName: string
  ) => {
    if (intervalId) {
      clearInterval(intervalId);
    }
    // Refresh every 30 seconds
    intervalId = setInterval(() => {
      get().fetchIndexes(connectionName, bucketName, scopeName);
    }, 30000);
  };

  const stopPolling = () => {
    if (intervalId) {
      clearInterval(intervalId);
      intervalId = null;
    }
  };

  return {
    indexes: null,
    error: null,
    retrievedAt: null,
    loading: false,

    fetchIndexes: async (
      connectionName: string,
      bucketName: string,
      scopeName: string
    ) => {
      try {
        set({ loading: true, error: null });

        console.log("🔍 ftsIndexStore: Fetching FTS indexes...", {
          connectionName,
          bucketName,
          scopeName,
        });

        const response = await fetch(
          `/api/fts/indexes?connectionName=${encodeURIComponent(
            connectionName
          )}&bucketName=${encodeURIComponent(
            bucketName
          )}&scopeName=${encodeURIComponent(scopeName)}`,
          {
            method: "GET",
            headers: {
              "Content-Type": "application/json",
            },
          }
        );

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const data: FtsIndexDetails[] = await response.json();
        console.log("✅ ftsIndexStore: FTS indexes received:", data);

        set((state) => {
          // Only update if data has actually changed
          const hasChanged =
            !state.indexes ||
            JSON.stringify(state.indexes) !== JSON.stringify(data);

          if (hasChanged) {
            return {
              indexes: data,
              error: null,
              retrievedAt: new Date(),
              loading: false,
            };
          }
          return { retrievedAt: new Date(), loading: false }; // Update timestamp only
        });

        // Start polling after first successful fetch
        if (!intervalId) {
          startPolling(connectionName, bucketName, scopeName);
        }
      } catch (err: any) {
        let errorMessage = "Failed to fetch FTS indexes";

        if (err.name === "AbortError") {
          errorMessage = "Request timed out - backend may not be responding";
        } else if (err.message.includes("404")) {
          errorMessage = "FTS indexes endpoint not available";
        } else if (err.message.includes("500")) {
          errorMessage = "Server error - check connection and credentials";
        }

        console.error("FTS indexes fetch error:", err);
        set({ error: errorMessage, loading: false });

        // Stop polling on error
        stopPolling();
      }
    },

    refreshIndexes: async (
      connectionName: string,
      bucketName: string,
      scopeName: string
    ) => {
      await get().fetchIndexes(connectionName, bucketName, scopeName);
    },

    clearError: () => {
      set({ error: null });
    },

    clearIndexes: () => {
      stopPolling();
      set({ indexes: null, error: null, retrievedAt: null, loading: false });
    },
  };
});

// Cleanup function for when the store is no longer needed
export const cleanupFtsIndexStore = () => {
  // Reset the store state and stop polling
  useFtsIndexStore.getState().clearIndexes();
  useFtsIndexStore.setState({
    indexes: null,
    error: null,
    retrievedAt: null,
    loading: false,
  });
};
