import { useState, useEffect } from "react";
import axios from "axios";

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

export const useConnection = () => {
  const [connection, setConnection] = useState<ConnectionInfo>({
    id: "No Connection",
    name: "No Connection",
    version: "No Connection",
    isConnected: false,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Check for existing connection on mount
  useEffect(() => {
    fetchConnection();
  }, []);

  const fetchConnection = async () => {
    setLoading(true);
    setError(null);

    try {
      const response = await axios.get("/api/connections/active");

      if (response.data.success && response.data.connections) {
        const backendConnections = response.data.connections || [];

        if (backendConnections.length > 0) {
          const connectionName = backendConnections[0];

          const connectionInfo: ConnectionInfo = {
            id: `conn-${connectionName}`,
            name: connectionName,
            version: "7.6.0", // Will be updated by metrics
            isConnected: true,
          };

          setConnection(connectionInfo);
        } else {
          setConnection({
            id: "No Connection",
            name: "No Connection",
            version: "No Connection",
            isConnected: false,
          });
        }
      } else {
        setConnection({
          id: "No Connection",
          name: "No Connection",
          version: "No Connection",
          isConnected: false,
        });
      }
    } catch (error: any) {
      console.error("[useConnection] Error fetching connection:", error);
      setError(error.response?.data?.error || "Failed to fetch connection");
      setConnection({
        id: "No Connection",
        name: "No Connection",
        version: "No Connection",
        isConnected: false,
      });
    } finally {
      setLoading(false);
    }
  };

  const createConnection = async (
    connectionRequest: ConnectionRequest
  ): Promise<{ success: boolean; error?: string; clusterName?: string }> => {
    setLoading(true);
    setError(null);

    try {
      // Replace 'localhost' with '127.0.0.1' in the connection string
      const safeConnectionString = connectionRequest.connectionString.replace(
        /localhost(?![\w.])/g,
        "127.0.0.1"
      );

      // Generate connection name if not provided
      const connectionName = `${
        connectionRequest.serverType || "Couchbase"
      }-${Date.now()}`;

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
          "[useConnection] Failed to store connection details for metrics:",
          metricsError
        );
      }

      // Update local connection state
      const connectionInfo: ConnectionInfo = {
        id: `conn-${clusterName}`,
        name: clusterName,
        version: "7.6.0", // Will be updated by metrics
        isConnected: true,
      };

      setConnection(connectionInfo);

      return { success: true, clusterName };
    } catch (error: any) {
      console.error("[useConnection] Error creating connection:", error);
      const errorMessage =
        error.response?.data?.error || "Failed to create connection";
      setError(errorMessage);
      return { success: false, error: errorMessage };
    } finally {
      setLoading(false);
    }
  };

  const deleteConnection = async (): Promise<void> => {
    if (!connection.isConnected) {
      return;
    }

    try {
      // Stop metrics polling (if implemented)
      // this.stopMetricsPolling();

      // Delete from backend
      await axios.delete(`/api/connections/${connection.name}`);
      await axios.delete(`/api/metrics/connection-details/${connection.name}`);

      // Clear connection
      setConnection({
        id: "No Connection",
        name: "No Connection",
        version: "No Connection",
        isConnected: false,
      });
    } catch (error: any) {
      console.error("[useConnection] Error deleting connection:", error);
      setError("Failed to delete connection");
    }
  };

  return {
    connection,
    loading,
    error,
    fetchConnection,
    createConnection,
    deleteConnection,
  };
};
