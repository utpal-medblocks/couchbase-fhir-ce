import React from "react";
import { Box, Typography } from "@mui/material";

import { useConnectionInfo } from "../Layout/DisplayContext";

const DashboardConnectionFooter: React.FC = () => {
  const connection = useConnectionInfo();

  if (!connection.isConnected) {
    return (
      <Box
        sx={{
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <Box sx={{ textAlign: "center" }}>
          <Typography variant="body2" color="text.secondary">
            No connection available. Please connect to a Couchbase cluster.
          </Typography>
        </Box>
      </Box>
    );
  }

  // For now, show a simple message until we implement metrics
  return (
    <Box
      sx={{ width: "100%", display: "flex", flexDirection: "column", gap: 3 }}
    >
      <Typography
        variant="subtitle1"
        align="center"
        sx={{
          pb: 1,
          lineHeight: 1,
          borderBottom: 1,
          borderBottomColor: "divider",
        }}
      >
        Connection Status
      </Typography>

      <Box sx={{ textAlign: "center" }}>
        <Typography variant="body2" color="success.main">
          Connected to {connection.name} ({connection.version})
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Metrics will be available once we implement the metrics service.
        </Typography>
      </Box>
    </Box>
  );
};

export default DashboardConnectionFooter;
