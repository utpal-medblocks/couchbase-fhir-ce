import { createContext, useContext, useState } from "react";
import type { ReactNode } from "react";
import { Box, Typography } from "@mui/material";
import { BsBucket, BsUnlock } from "react-icons/bs";
import { AiOutlineCluster } from "react-icons/ai";
import flame from "../../assets/icons/flame.png";
import { useConnection } from "../../hooks/useConnection";

// Modal dialogs
import UnifiedConnectionDialog from "../Connections/UnifiedConnectionDialog";

interface DisplayContextType {
  connectionDialog: boolean;
  setConnectionDialog: (open: boolean) => void;
}

const DisplayContext = createContext<DisplayContextType | undefined>(undefined);

export const DisplayProvider = ({ children }: { children: ReactNode }) => {
  const [connectionDialog, setConnectionDialog] = useState(false);
  const { connection, createConnection } = useConnection();

  const handleConnectionSuccess = async (connectionRequest: {
    connectionString: string;
    username: string;
    password: string;
    serverType: "Server" | "Capella";
    sslEnabled: boolean;
  }) => {
    console.log("[DisplayProvider] Creating connection...");
    const result = await createConnection(connectionRequest);

    if (result.success) {
      console.log("[DisplayProvider] Connection created successfully");
      setConnectionDialog(false);
    } else {
      console.error(
        "[DisplayProvider] Failed to create connection:",
        result.error
      );
    }
  };

  const handleConnectionClose = () => {
    // Only allow closing if we have a connection
    if (connection.isConnected) {
      setConnectionDialog(false);
    }
  };

  return (
    <DisplayContext.Provider
      value={{
        connectionDialog,
        setConnectionDialog,
      }}
    >
      {children}

      {/* Connection Dialog */}
      <UnifiedConnectionDialog
        open={connectionDialog}
        onClose={handleConnectionClose}
        onSuccess={handleConnectionSuccess}
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

// Export connection from the hook for components that need it
export const useConnectionInfo = () => {
  const { connection } = useConnection();
  return connection;
};

const DisplayContextComponent = () => {
  const { connection } = useConnection();

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
