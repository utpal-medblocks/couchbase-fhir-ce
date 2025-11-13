import { createContext, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import InitializationDialog from "../../components/InitializationDialog";

interface ConnectionContextType {
  // Connection management context
}

const ConnectionContext = createContext<ConnectionContextType | undefined>(
  undefined
);

export const ConnectionProvider = ({ children }: { children: ReactNode }) => {
  const { fetchConnection } = useConnectionStore();
  const { fetchInitializationStatus, initializationStatus } = useBucketStore();
  const pollingIntervalRef = useRef<number | null>(null);
  const [showInitDialog, setShowInitDialog] = useState(false);
  const [hasCheckedInit, setHasCheckedInit] = useState(false);

  // Start connection polling on mount
  useEffect(() => {
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
  }, [fetchConnection]);

  // Check initialization status after connection is established
  useEffect(() => {
    const checkInitialization = async () => {
      if (!hasCheckedInit) {
        console.log("🚀 Checking FHIR system initialization status...");

        // Wait a bit for backend to be ready
        await new Promise((resolve) => setTimeout(resolve, 1000));

        try {
          const status = await fetchInitializationStatus();
          console.log("🚀 Initialization status:", status.status);

          // Show dialog if not ready
          if (status.status !== "READY") {
            setShowInitDialog(true);
          }

          setHasCheckedInit(true);
        } catch (error) {
          console.error("Failed to check initialization status:", error);
        }
      }
    };

    checkInitialization();
  }, [hasCheckedInit, fetchInitializationStatus]);

  const handleCloseInitDialog = () => {
    setShowInitDialog(false);

    // If they closed without completing, allow checking again later
    if (initializationStatus?.status !== "READY") {
      setHasCheckedInit(false);
    }
  };

  return (
    <ConnectionContext.Provider value={{}}>
      {children}
      <InitializationDialog
        open={showInitDialog}
        onClose={handleCloseInitDialog}
      />
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
