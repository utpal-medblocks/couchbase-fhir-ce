import type { FtsIndexDetails, FtsIndex } from "../store/ftsIndexStore";

export interface FtsIndexStatsResponse {
  stats: Record<string, any>;
}

class FtsIndexService {
  private baseUrl = "/api/fts";

  /**
   * Get all FTS index details for a specific bucket and scope
   */
  async getFtsIndexDetails(
    connectionName: string,
    bucketName: string,
    scopeName: string
  ): Promise<FtsIndexDetails[]> {
    const params = new URLSearchParams({
      connectionName,
      bucketName,
      scopeName,
    });

    const response = await fetch(`${this.baseUrl}/indexes?${params}`, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
      },
    });

    if (!response.ok) {
      throw new Error(
        `Failed to fetch FTS index details: ${response.statusText}`
      );
    }

    return response.json();
  }

  /**
   * Get FTS index definitions only (without stats)
   */
  async getFtsIndexDefinitions(
    connectionName: string,
    bucketName: string,
    scopeName: string
  ): Promise<FtsIndex[]> {
    const params = new URLSearchParams({
      connectionName,
      bucketName,
      scopeName,
    });

    const response = await fetch(`${this.baseUrl}/definitions?${params}`, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
      },
    });

    if (!response.ok) {
      throw new Error(
        `Failed to fetch FTS index definitions: ${response.statusText}`
      );
    }

    return response.json();
  }

  /**
   * Get FTS index statistics only
   */
  async getFtsIndexStats(
    connectionName: string
  ): Promise<FtsIndexStatsResponse> {
    const params = new URLSearchParams({
      connectionName,
    });

    const response = await fetch(`${this.baseUrl}/stats?${params}`, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
      },
    });

    if (!response.ok) {
      throw new Error(
        `Failed to fetch FTS index stats: ${response.statusText}`
      );
    }

    return response.json();
  }
}

export const ftsIndexService = new FtsIndexService();
export default ftsIndexService;
