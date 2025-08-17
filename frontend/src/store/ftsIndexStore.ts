import { create } from "zustand";
import {
  getFtsProgress,
  type FtsProgressData,
} from "../services/ftsProgressService";

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

  // Progress data from CB console API
  progress?: {
    doc_count: number;
    ingest_status: string;
    tot_seq_received: number;
    num_mutations_to_index: number;
    error?: string;
  };

  // Note: All metrics (queryLatency, queryRate, totalQueries, diskSize, etc.)
  // are now handled by the dedicated metrics endpoint
}

interface FtsIndexState {
  indexes: FtsIndexDetails[] | null;
  error: string | null;
  retrievedAt: Date | null;
  loading: boolean;
  progressLoading: boolean;

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
  fetchProgress: (
    connectionName: string,
    bucketName: string,
    scopeName?: string
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
    progressLoading: false,

    fetchIndexes: async (
      connectionName: string,
      bucketName: string,
      scopeName: string
    ) => {
      try {
        set({ loading: true, error: null });

        // console.log("🔍 ftsIndexStore: Fetching FTS indexes...", {
        //   connectionName,
        //   bucketName,
        //   scopeName,
        // });

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
        // console.log("✅ ftsIndexStore: FTS indexes received:", data);

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

    fetchProgress: async (
      connectionName: string,
      bucketName: string,
      scopeName: string = "Resources"
    ) => {
      const { indexes, progressLoading } = get();

      if (!indexes || !indexes.length) {
        return;
      }

      try {
        // Only set loading to true if no progress data exists yet (initial load)
        const hasProgressData = indexes.some((idx) => idx.progress);
        if (!hasProgressData) {
          set({ progressLoading: true });
        }

        const indexNames = indexes.map((idx) => idx.indexName);
        const progressData = await getFtsProgress(
          connectionName,
          indexNames,
          bucketName,
          scopeName
        );

        // Update indexes with progress data
        const updatedIndexes = indexes.map((index) => {
          const progress = progressData.results.find(
            (p) => p.indexName === index.indexName
          );

          if (progress) {
            return {
              ...index,
              progress: {
                doc_count: progress.doc_count,
                ingest_status: progress.ingest_status,
                tot_seq_received: progress.tot_seq_received,
                num_mutations_to_index: progress.num_mutations_to_index,
                error: progress.error,
              },
            };
          }

          return index;
        });

        set({
          indexes: updatedIndexes,
          progressLoading: false,
        });
      } catch (err: any) {
        console.error("FTS progress fetch error:", err.message);
        set({ progressLoading: false });
        // Don't set error state for progress failures - allows table to still show index names
      }
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
