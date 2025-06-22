import { useState, useEffect, useRef } from "react";
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

// Global cache to prevent duplicate API calls
let connectionCache: {
  data: ConnectionInfo | null;
  timestamp: number;
  promise: Promise<ConnectionInfo> | null;
} = {
  data: null,
  timestamp: 0,
  promise: null,
};

const CACHE_DURATION = 5000; // 5 seconds cache

export const useConnection = () => {
  const [connection, setConnection] = useState<ConnectionInfo>({
    id: "No Connection",
    name: "No Connection",
    version: "No Connection",
    isConnected: false,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isInitialized = useRef(false);

  // Check for existing connection on mount
  useEffect(() => {
    if (!isInitialized.current) {
      isInitialized.current = true;
      fetchConnection();
    }
  }, []);

  const fetchConnection = async () => {
    console.log("🔗 useConnection: Starting fetchConnection");
    const startTime = performance.now();

    // Check cache first
    const now = Date.now();
    if (
      connectionCache.data &&
      now - connectionCache.timestamp < CACHE_DURATION
    ) {
      console.log("📦 useConnection: Using cached connection data");
      setConnection(connectionCache.data);
      setLoading(false);
      return;
    }

    // If there's already a pending request, wait for it
    if (connectionCache.promise) {
      console.log("⏳ useConnection: Waiting for existing request");
      try {
        const cachedData = await connectionCache.promise;
        setConnection(cachedData);
        setLoading(false);
        return;
      } catch (error) {
        console.log(
          "❌ useConnection: Cached request failed, making new request"
        );
      }
    }

    setLoading(true);
    setError(null);

    try {
      // Add timeout to prevent long hangs
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 3000); // 3 second timeout

      console.log("🌐 useConnection: Making API call to /api/connections");
      const apiStartTime = performance.now();

      // Create the promise and store it in cache
      const fetchPromise = axios
        .get("/api/connections", {
          signal: controller.signal,
          timeout: 3000,
        })
        .then(async (response) => {
          const apiEndTime = performance.now();
          console.log(
            `✅ useConnection: API call completed in ${(
              apiEndTime - apiStartTime
            ).toFixed(2)}ms`
          );

          clearTimeout(timeoutId);

          if (response.data && Array.isArray(response.data)) {
            const connections = response.data;

            if (connections.length > 0) {
              // For now, use the first connection as the active one
              // TODO: Add logic to determine which connection is actually active
              const activeConnection = connections[0];

              const connectionInfo: ConnectionInfo = {
                id: activeConnection.id || "unknown",
                name: activeConnection.connectionString || "Unknown Connection",
                version: "7.6.0", // This should come from the connection data
                isConnected: true, // Assume connected if it exists
              };

              // Cache the result
              connectionCache.data = connectionInfo;
              connectionCache.timestamp = now;
              connectionCache.promise = null;

              return connectionInfo;
            } else {
              // No connections available
              const noConnection: ConnectionInfo = {
                id: "No Connection",
                name: "No Connection",
                version: "No Connection",
                isConnected: false,
              };

              connectionCache.data = noConnection;
              connectionCache.timestamp = now;
              connectionCache.promise = null;

              return noConnection;
            }
          } else {
            const noConnection: ConnectionInfo = {
              id: "No Connection",
              name: "No Connection",
              version: "No Connection",
              isConnected: false,
            };

            connectionCache.data = noConnection;
            connectionCache.timestamp = now;
            connectionCache.promise = null;

            return noConnection;
          }
        });

      // Store the promise in cache
      connectionCache.promise = fetchPromise;

      const result = await fetchPromise;
      setConnection(result);
    } catch (error: any) {
      const endTime = performance.now();
      console.log(
        `❌ useConnection: fetchConnection failed after ${(
          endTime - startTime
        ).toFixed(2)}ms`
      );

      // Clear the failed promise
      connectionCache.promise = null;

      // Handle different types of errors more specifically
      if (error.name === "AbortError" || error.code === "ECONNABORTED") {
        console.warn("[useConnection] Request timed out - backend may be slow");
        setError("Connection request timed out");
      } else if (error.response?.status === 404) {
        console.warn("[useConnection] Backend not available (404)");
        setError("Backend not available");
      } else {
        console.error("[useConnection] Error fetching connection:", error);
        setError(error.response?.data?.error || "Failed to fetch connection");
      }
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
