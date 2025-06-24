import { createContext, useContext, useEffect } from "react";
import type { ReactNode } from "react";
import { Box, Typography } from "@mui/material";
import { BsBucket, BsUnlock } from "react-icons/bs";
import { AiOutlineCluster } from "react-icons/ai";
import flame from "../../assets/icons/flame.png";
import { useConnectionStore } from "../../store/connectionStore";

// Modal dialogs
import UnifiedConnectionDialog from "../Connections/UnifiedConnectionDialog";

interface DisplayContextType {
  // Connection dialog is now managed by Zustand store
}

const DisplayContext = createContext<DisplayContextType | undefined>(undefined);

export const DisplayProvider = ({ children }: { children: ReactNode }) => {
  const {
    connection,
    error,
    showDialog,
    fetchConnection,
    createConnection,
    setShowDialog,
  } = useConnectionStore();

  // Initialize connection on mount
  useEffect(() => {
    console.log("🔍 DisplayProvider: Initializing connection");
    fetchConnection();
  }, [fetchConnection]);

  // Show dialog if no connection exists
  useEffect(() => {
    console.log("🔍 DisplayProvider: useEffect triggered", {
      isConnected: connection.isConnected,
      error,
      showDialog,
      connectionName: connection.name,
    });

    if (!connection.isConnected && !error && !showDialog) {
      console.log("🔍 DisplayProvider: Showing connection dialog");
      setShowDialog(true);
    }
  }, [connection.isConnected, error, showDialog, setShowDialog]);

  const handleDialogSuccess = async (connectionRequest: {
    connectionString: string;
    username: string;
    password: string;
    serverType: "Server" | "Capella";
    sslEnabled: boolean;
  }) => {
    const result = await createConnection(connectionRequest);
    return result;
  };

  const handleDialogClose = () => {
    if (connection.isConnected) {
      setShowDialog(false);
    }
  };

  return (
    <DisplayContext.Provider value={{}}>
      {children}

      {/* Connection Dialog */}
      <UnifiedConnectionDialog
        open={showDialog}
        onClose={handleDialogClose}
        onSuccess={handleDialogSuccess}
      />
    </DisplayContext.Provider>
  );
};

export const useDisplayContext = () => {
  const context = useContext(DisplayContext);
  if (!context) {
    throw new Error("useDisplayContext must be used within a DisplayProvider");
  }
  return context;
};

// Export connection from the store for components that need it
export const useConnectionInfo = () => {
  const { connection } = useConnectionStore();
  return connection;
};

const DisplayContextComponent = () => {
  const { connection } = useConnectionStore();

  return (
    <Box sx={{ marginLeft: 4, display: "flex", flexDirection: "column" }}>
      {/* First line - Connection info */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "flex-start",
          alignItems: "center",
          gap: "8px",
        }}
      >
        <AiOutlineCluster style={{ fontSize: "20px" }} />
        <Typography variant="body2">
          <b>Server</b> {connection.name} {connection.version}
        </Typography>
        <BsUnlock style={{ fontSize: "14px" }} />
        <BsBucket style={{ fontSize: "16px" }} />
        <Typography variant="body2">
          <b>Bucket</b>
        </Typography>
        <Typography
          variant="body2"
          sx={{
            marginRight: 2,
            fontWeight: "normal",
            color: "text.secondary",
            fontStyle: "italic",
          }}
        >
          Not Set
        </Typography>
      </Box>

      {/* Second line - Bucket/Scope info */}
      <Box sx={{ display: "flex", alignItems: "center", gap: "8px" }}>
        <img src={flame} alt="flame" style={{ width: 16, height: 16 }} />
        <Typography variant="body2">
          <b>FHIR</b> V4 <b>Profile</b> US Core <b>Endpoint</b> /fhir/demo
        </Typography>
      </Box>
    </Box>
  );
};

export default DisplayContextComponent;
