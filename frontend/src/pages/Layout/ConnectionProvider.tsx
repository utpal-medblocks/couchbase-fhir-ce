import { createContext, useContext, useEffect } from "react";
import type { ReactNode } from "react";
import { useConnectionStore } from "../../store/connectionStore";
import { useConfigStore } from "../../store/configStore";
import UnifiedConnectionDialog from "../Connections/UnifiedConnectionDialog";

interface ConnectionContextType {
  // Connection management context
}

const ConnectionContext = createContext<ConnectionContextType | undefined>(
  undefined
);

export const ConnectionProvider = ({ children }: { children: ReactNode }) => {
  const {
    connection,
    error,
    showDialog,
    fetchConnection,
    createConnection,
    setShowDialog,
  } = useConnectionStore();

  const { yamlConfig, configLoaded, configError, loadYamlConfig } =
    useConfigStore();

  // Step 1: Load YAML configuration on mount
  useEffect(() => {
    loadYamlConfig();
  }, [loadYamlConfig]);

  // Step 2: Once config is loaded, try to connect automatically if YAML config exists
  useEffect(() => {
    if (configLoaded && yamlConfig && !connection.isConnected && !error) {
      console.log(
        "🔗 ConnectionProvider: Attempting auto-connection from YAML config"
      );

      // Auto-connect using YAML configuration
      createConnection(yamlConfig.connection).then((result) => {
        if (!result.success) {
          console.warn(
            "⚠️ Auto-connection failed, will show dialog:",
            result.error
          );
          // If auto-connection fails, show dialog (unless explicitly disabled)
          if (yamlConfig.app?.showConnectionDialog !== false) {
            setShowDialog(true);
          }
        } else {
          console.log("✅ Auto-connection successful from YAML config");
        }
      });
    }
  }, [
    configLoaded,
    yamlConfig,
    connection.isConnected,
    error,
    createConnection,
    setShowDialog,
  ]);

  // Step 3: Show dialog if no connection exists and no YAML config or auto-connection failed
  useEffect(() => {
    if (
      configLoaded && // Wait for config to load
      !connection.isConnected &&
      !error &&
      !showDialog &&
      (!yamlConfig || yamlConfig.app?.showConnectionDialog !== false) // Show dialog unless explicitly disabled
    ) {
      console.log("🔗 ConnectionProvider: Showing connection dialog");
      setShowDialog(true);
    }
  }, [
    configLoaded,
    yamlConfig,
    connection.isConnected,
    error,
    showDialog,
    setShowDialog,
  ]);

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
    <ConnectionContext.Provider value={{}}>
      {children}

      {/* Connection Dialog */}
      <UnifiedConnectionDialog
        open={showDialog}
        onClose={handleDialogClose}
        onSuccess={handleDialogSuccess}
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
