/**
 * Service for FTS index progress API calls
 */

export interface FtsProgressData {
  indexName: string;
  doc_count: number;
  tot_seq_received: number;
  num_mutations_to_index: number;
  ingest_status: "idle" | "indexing" | "paused" | "error" | string;
  error?: string;
}

export interface FtsProgressRequest {
  connectionName: string;
  indexNames: string[];
  bucketName: string;
  scopeName: string;
}

export interface FtsProgressResponse {
  results: FtsProgressData[];
}

/**
 * Fetch progress data for multiple FTS indexes
 */
export const getFtsProgress = async (
  connectionName: string,
  indexNames: string[],
  bucketName: string,
  scopeName: string = "Resources"
): Promise<FtsProgressResponse> => {
  if (!connectionName || !indexNames.length) {
    return { results: [] };
  }

  const requestBody: FtsProgressRequest = {
    connectionName,
    indexNames,
    bucketName,
    scopeName,
  };

  try {
    const response = await fetch("/api/fts/progress", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(requestBody),
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const data: FtsProgressResponse = await response.json();
    return data;
  } catch (error) {
    console.error("Failed to fetch FTS progress:", error);
    throw error;
  }
};

/**
 * Format ingest status for display with appropriate emoji/icon
 */
export const formatIngestStatus = (
  status: string | undefined | null
): string => {
  if (!status) {
    return "❓ Unknown";
  }

  switch (status.toLowerCase()) {
    case "idle":
      return "✅ Idle";
    case "active":
    case "indexing":
      return "🔄 Active";
    case "paused":
      return "⏸️ Paused";
    case "error":
    case "failed":
      return "❌ Error";
    case "stopped":
      return "⏹️ Stopped";
    default:
      return `🔧 ${status}`;
  }
};

/**
 * Format document count for display
 */
export const formatDocCount = (count: number | undefined | null): string => {
  if (count === null || count === undefined || isNaN(count)) {
    return "0";
  }
  return count.toLocaleString();
};
