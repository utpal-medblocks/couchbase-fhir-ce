import React, { useState } from "react";
import { Box, Typography, Alert, AlertTitle } from "@mui/material";
import SyntheaSamplesCard from "../../components/SyntheaSamplesCard";
import LoadSamplesDialog from "../../components/LoadSamplesDialog";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";

const Samples: React.FC = () => {
  const [dialogOpen, setDialogOpen] = useState(false);

  // Get connection and bucket information
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();
  const connectionId = connection.name;
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const activeScope = bucketStore.getActiveScope(connectionId);

  const handleCardClick = () => {
    setDialogOpen(true);
  };

  const handleDialogClose = () => {
    setDialogOpen(false);
  };

  const handleLoadSuccess = () => {
    // Refresh bucket data after successful load
    bucketStore.fetchBucketData(connectionId);

    // Close dialog after a short delay
    setTimeout(() => {
      setDialogOpen(false);
    }, 2000);
  };

  // Check if we have required selections
  const hasRequiredSelections = activeBucket && activeScope;
  const isConnected = connection.isConnected;

  if (!isConnected) {
    return (
      <Box sx={{ p: 2 }}>
        <Alert severity="warning">
          <AlertTitle>Connection Required</AlertTitle>
          Please establish a connection to load sample data.
        </Alert>
      </Box>
    );
  }

  if (!hasRequiredSelections) {
    return (
      <Box sx={{ p: 2 }}>
        <Alert severity="info">
          <AlertTitle>Bucket and Scope Selection Required</AlertTitle>
          Please select a FHIR bucket and scope to load sample data.
        </Alert>
      </Box>
    );
  }

  return (
    <Box sx={{ p: 2, height: "100%" }}>
      <SyntheaSamplesCard
        onClick={handleCardClick}
        disabled={!hasRequiredSelections}
      />

      {/* Load Samples Dialog */}
      <LoadSamplesDialog
        open={dialogOpen}
        onClose={handleDialogClose}
        bucketName={activeBucket?.bucketName || ""}
        connectionName={connectionId}
        onSuccess={handleLoadSuccess}
      />
    </Box>
  );
};

export default Samples;
