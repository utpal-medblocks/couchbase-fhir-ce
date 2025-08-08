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
  // Core fields needed for table display
  indexName: string;
  status: string;
  docsIndexed: number;
  lastTimeUsed: string;

  // Context fields needed for metrics and tree view
  bucketName: string;
  indexDefinition: FtsIndex;

  // Note: All metrics (queryLatency, queryRate, totalQueries, diskSize, etc.)
  // are now handled by the dedicated metrics endpoint
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
  // Removed automatic polling - FTS indexes don't change frequently
  // Users can manually refresh when needed

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

        set({
          indexes: data,
          error: null,
          retrievedAt: new Date(),
          loading: false,
        });
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
