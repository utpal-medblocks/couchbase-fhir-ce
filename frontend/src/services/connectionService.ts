import axios from "axios";

interface ConnectionRequest {
  connectionString: string;
  username: string;
  password: string;
  serverType: "Server" | "Capella";
  sslEnabled: boolean;
  name?: string;
}

interface ConnectionResponse {
  success: boolean;
  message?: string;
  error?: string;
  clusterName?: string;
}

export class ConnectionService {
  private static instance: ConnectionService;

  static getInstance(): ConnectionService {
    if (!ConnectionService.instance) {
      ConnectionService.instance = new ConnectionService();
    }
    return ConnectionService.instance;
  }

  /**
   * Create a new connection
   */
  async createConnection(
    connectionRequest: ConnectionRequest
  ): Promise<ConnectionResponse> {
    try {
      // Replace 'localhost' with '127.0.0.1' in the connection string
      const safeConnectionString = connectionRequest.connectionString.replace(
        /localhost(?![\w.])/g,
        "127.0.0.1"
      );

      // Generate connection name if not provided
      const connectionName =
        connectionRequest.name ||
        `${connectionRequest.serverType || "Couchbase"}-${Date.now()}`;

      const safeRequest = {
        ...connectionRequest,
        name: connectionName,
        connectionString: safeConnectionString,
      };

      // Call the create endpoint
      const createResponse = await axios.post(
        "/api/connections/create",
        safeRequest
      );

      if (!createResponse.data.success) {
        return { success: false, error: createResponse.data.error };
      }

      // Extract cluster name from response if available
      const clusterName = createResponse.data.clusterName || connectionName;

      // Store connection details for metrics
      try {
        await axios.post("/api/metrics/connection-details", {
          name: clusterName,
          connectionString: safeRequest.connectionString,
          username: safeRequest.username,
          password: safeRequest.password,
        });
      } catch (metricsError) {
        console.warn(
          "[createConnection] Failed to store connection details for metrics:",
          metricsError
        );
      }

      return { success: true, clusterName };
    } catch (error: any) {
      console.error("[createConnection] Error:", error);
      return {
        success: false,
        error: error.response?.data?.error || "Failed to create connection",
      };
    }
  }

  /**
   * Get active connections
   */
  async getActiveConnections(): Promise<{
    success: boolean;
    connections?: string[];
    error?: string;
  }> {
    try {
      const response = await axios.get("/api/connections/active");

      if (response.data && response.data.success) {
        return {
          success: true,
          connections: response.data.connections || [],
        };
      } else {
        return {
          success: false,
          error: response.data?.error || "Failed to get active connections",
        };
      }
    } catch (error: any) {
      console.error("[getActiveConnections] Error:", error);
      return {
        success: false,
        error:
          error.response?.data?.error || "Failed to get active connections",
      };
    }
  }

  /**
   * Delete a connection
   */
  async deleteConnection(
    connectionName: string
  ): Promise<{ success: boolean; error?: string }> {
    try {
      await axios.delete(`/api/connections/${connectionName}`);
      await axios.delete(`/api/metrics/connection-details/${connectionName}`);

      return { success: true };
    } catch (error: any) {
      console.error("[deleteConnection] Error:", error);
      return {
        success: false,
        error: error.response?.data?.error || "Failed to delete connection",
      };
    }
  }

  /**
   * Get cluster metrics
   */
  async getClusterMetrics(connectionName: string): Promise<any> {
    try {
      const response = await axios.get(
        `/api/metrics/cluster/${connectionName}`
      );
      return response.data;
    } catch (error: any) {
      console.error("[getClusterMetrics] Error:", error);
      throw error;
    }
  }
}

// Export singleton instance
export const connectionService = ConnectionService.getInstance();
