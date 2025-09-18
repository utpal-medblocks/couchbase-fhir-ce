import { Box, Typography } from "@mui/material";
import { BsUnlock, BsLock } from "react-icons/bs";
import { AiOutlineCluster } from "react-icons/ai";
import flame from "../../assets/icons/flame.png";
import { useConnectionStore } from "../../store/connectionStore";

const ConnectionStatus = () => {
  const { connection, metrics } = useConnectionStore();

  const nodes = metrics?.nodes || [];
  const clusterName = connection.name;
  const clusterVersion = nodes.length > 0 ? nodes[0].version : "No Connection";

  // Static FHIR configuration - backend manages the actual config
  const fhirConfig = {
    profile: "US Core",
    endpoint: "/fhir/demo",
    version: "V4",
  };

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
          <b>Server</b> {clusterName} {clusterVersion}
        </Typography>
        {connection.isConnected &&
          (connection.isSSL ? (
            <BsLock
              style={{ fontSize: "14px", color: "#4caf50" }}
              title="SSL/TLS Enabled"
            />
          ) : (
            <BsUnlock
              style={{ fontSize: "14px", color: "#ff9800" }}
              title="SSL/TLS Disabled"
            />
          ))}
      </Box>

      {/* Second line - FHIR info from YAML config or defaults */}
      <Box sx={{ display: "flex", alignItems: "center", gap: "8px" }}>
        <img src={flame} alt="flame" style={{ width: 16, height: 16 }} />
        <Typography variant="body2">
          <b>FHIR</b> {fhirConfig.version} <b>Profile</b> {fhirConfig.profile}{" "}
          <b>Endpoint</b> {fhirConfig.endpoint}
        </Typography>
      </Box>
    </Box>
  );
};

export default ConnectionStatus;
