import { createContext, useContext, useEffect, useRef } from "react";
import type { ReactNode } from "react";
import { useConnectionStore } from "../../store/connectionStore";
import { useAuthStore } from "../../store/authStore";

interface ConnectionContextType {
  // Connection management context
}

const ConnectionContext = createContext<ConnectionContextType | undefined>(
  undefined
);

export const ConnectionProvider = ({ children }: { children: ReactNode }) => {
  const { fetchConnection } = useConnectionStore();
  const { isAuthenticated } = useAuthStore();
  const pollingIntervalRef = useRef<number | null>(null);

  // Start connection polling on mount - only if authenticated
  useEffect(() => {
    // Skip if user is not authenticated (e.g., on login page)
    if (!isAuthenticated) {
      console.log("🔗 ConnectionProvider: Skipping - user not authenticated");
      return;
    }

    console.log("🔗 ConnectionProvider: Starting connection polling");

    // Initial check immediately
    fetchConnection().catch((error) => {
      console.log(
        "🔗 Initial connection check failed (expected if BE still starting):",
        error.message
      );
    });

    // Set up polling every 20 seconds
    // Note: We continue polling even after connection to detect backend failures
    pollingIntervalRef.current = setInterval(() => {
      console.log("🔗 ConnectionProvider: Polling for backend connection...");
      fetchConnection().catch((error) => {
        console.log("🔗 Connection poll failed:", error.message);
      });
    }, 20000); // 20 seconds - TODO: Could reduce to 60s once connected

    // Cleanup on unmount
    return () => {
      if (pollingIntervalRef.current) {
        console.log("🔗 ConnectionProvider: Stopping connection polling");
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, [fetchConnection, isAuthenticated]);

  return (
    <ConnectionContext.Provider value={{}}>
      {children}
    </ConnectionContext.Provider>
  );
};

export const useConnectionContext = () => {
  const context = useContext(ConnectionContext);
  if (!context) {
    throw new Error(
      "useConnectionContext must be used within a ConnectionProvider"
    );
  }
  return context;
};
