import React from "react";
import { Tabs, Tab, Box, Alert, AlertTitle } from "@mui/material";
import BucketsMain from "./BucketsMain";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";

const Buckets = () => {
  const [selectedTab, setSelectedTab] = React.useState(0);

  const handleChange = (_event: React.SyntheticEvent, newValue: number) => {
    setSelectedTab(newValue);
  };

  // Get connection info from the new connection store
  const connection = useConnectionStore((state) => state.connection);
  const connectionId = connection.name;

  // Get bucket store data
  const bucketStore = useBucketStore();
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const activeScope = bucketStore.getActiveScope(connectionId);

  // Check if we have a valid connection
  if (!connection.isConnected) {
    return (
      <Alert severity="warning">
        <AlertTitle>No Active Connection</AlertTitle>
        Please establish a <strong>Connection</strong> first
      </Alert>
    );
  }

  // Note: We don't check for FHIR buckets here anymore
  // BucketsMain will handle loading and empty states

  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        flexGrow: 1,
        width: "100%",
        height: "100%",
        m: 0,
        p: 0,
      }}
    >
      <Tabs value={selectedTab} onChange={handleChange}>
        <Tab sx={{ textTransform: "none", margin: 0 }} label="Buckets" />
        <Tab
          disabled={!activeBucket || !activeScope}
          sx={{ textTransform: "none", margin: 0 }}
          label="GSI Indexes"
        />
        <Tab
          disabled={!activeBucket || !activeScope}
          sx={{ textTransform: "none", margin: 0 }}
          label="Schema"
        />
        <Tab
          disabled={!activeBucket || !activeScope}
          sx={{ textTransform: "none", margin: 0 }}
          label="FTS Indexes"
        />
      </Tabs>

      {/* Tab Content */}
      {selectedTab === 0 && <BucketsMain />}
      {selectedTab === 1 && <GSIIndexes />}
      {selectedTab === 2 && <SchemaManager />}
      {selectedTab === 3 && <FTSIndexes />}
    </Box>
  );
};

// Placeholder components for the other tabs
const GSIIndexes = () => (
  <Box p={2}>
    <Alert severity="info">
      <AlertTitle>GSI Indexes</AlertTitle>
      GSI Indexes management will be implemented here.
    </Alert>
  </Box>
);

const SchemaManager = () => (
  <Box p={2}>
    <Alert severity="info">
      <AlertTitle>Schema Management</AlertTitle>
      Schema management will be implemented here.
    </Alert>
  </Box>
);

const FTSIndexes = () => (
  <Box p={2}>
    <Alert severity="info">
      <AlertTitle>Full Text Search Indexes</AlertTitle>
      FTS Indexes management will be implemented here.
    </Alert>
  </Box>
);

export default Buckets;
