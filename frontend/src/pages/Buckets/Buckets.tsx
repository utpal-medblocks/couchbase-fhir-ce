import React, { useEffect } from "react";
import {
  Tabs,
  Tab,
  Box,
  Alert,
  AlertTitle,
  Typography,
  FormControl,
  Select,
  MenuItem,
} from "@mui/material";
import BucketsMain from "./BucketsMain";
import Samples from "./Samples";
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
  const fhirBuckets = bucketStore.getFhirBuckets(connectionId);
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const activeScope = bucketStore.getActiveScope(connectionId);

  // Handle bucket selection
  const handleBucketChange = (bucketName: string) => {
    bucketStore.setActiveBucket(connectionId, bucketName);
  };

  // Handle scope selection
  const handleScopeChange = (scopeName: string) => {
    bucketStore.setActiveScope(connectionId, scopeName);
  };

  // Refresh data
  const handleRefresh = async () => {
    try {
      await bucketStore.fetchBucketData(connectionId);
    } catch (error) {
      console.error("Failed to refresh bucket data:", error);
    }
  };

  // Effect to load initial data and set up refresh interval
  useEffect(() => {
    if (!connection.isConnected) {
      return;
    }

    // Load initial data
    handleRefresh();

    // Set up 30-second refresh interval
    const interval = setInterval(() => {
      handleRefresh();
    }, 30000); // 30 seconds

    // Cleanup interval on unmount or connection change
    return () => clearInterval(interval);
  }, [connection.isConnected, connectionId]);

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
      {/* Tabs with Bucket/Scope Selectors */}
      <Box
        sx={{
          display: "flex",
          alignItems: "center",
          borderBottom: 1,
          borderColor: "divider",
          minHeight: 48,
        }}
      >
        <Tabs value={selectedTab} onChange={handleChange} sx={{ flexGrow: 1 }}>
          <Tab
            sx={{
              textTransform: "none",
              margin: 0,
              "&:focus": {
                outline: "none",
              },
            }}
            label="Collections"
          />
          <Tab
            disabled={!activeBucket || !activeScope}
            sx={{
              textTransform: "none",
              margin: 0,
              "&:focus": {
                outline: "none",
              },
            }}
            label="Samples"
          />
          <Tab
            disabled={!activeBucket || !activeScope}
            sx={{
              textTransform: "none",
              margin: 0,
              "&:focus": {
                outline: "none",
              },
            }}
            label="GSI Indexes"
          />
          <Tab
            disabled={!activeBucket || !activeScope}
            sx={{
              textTransform: "none",
              margin: 0,
              "&:focus": {
                outline: "none",
              },
            }}
            label="Schema"
          />
          <Tab
            disabled={!activeBucket || !activeScope}
            sx={{
              textTransform: "none",
              margin: 0,
              "&:focus": {
                outline: "none",
              },
            }}
            label="FTS Indexes"
          />
        </Tabs>

        {/* Bucket and Scope selectors on the right */}
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, pr: 2 }}>
          <Typography variant="body2" sx={{ color: "primary.main" }}>
            FHIR Bucket
          </Typography>
          <FormControl
            variant="standard"
            sx={{
              minWidth: 150,
              color: "GrayText",
              "& .MuiSelect-select": {
                paddingBottom: 0,
              },
            }}
            size="small"
          >
            <Select
              value={activeBucket?.bucketName || ""}
              onChange={(e) => handleBucketChange(e.target.value)}
            >
              {fhirBuckets.map((bucket) => (
                <MenuItem key={bucket.bucketName} value={bucket.bucketName}>
                  {bucket.bucketName}
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          <Typography variant="body2" sx={{ color: "primary.main" }}>
            Scope
          </Typography>
          <FormControl
            variant="standard"
            sx={{
              minWidth: 150,
              color: "GrayText",
              "& .MuiSelect-select": {
                paddingBottom: 0,
              },
            }}
            size="small"
          >
            <Select
              value={activeScope || ""}
              onChange={(e) => handleScopeChange(e.target.value)}
              disabled={!activeBucket}
            >
              <MenuItem value="Admin">Admin</MenuItem>
              <MenuItem value="Resources">Resources</MenuItem>
            </Select>
          </FormControl>
        </Box>
      </Box>

      {/* Tab Content */}
      <Box sx={{ flexGrow: 1, overflow: "hidden" }}>
        {selectedTab === 0 && <BucketsMain />}
        {selectedTab === 1 && <Samples />}
        {selectedTab === 2 && <GSIIndexes />}
        {selectedTab === 3 && <SchemaManager />}
        {selectedTab === 4 && <FTSIndexes />}
      </Box>
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
