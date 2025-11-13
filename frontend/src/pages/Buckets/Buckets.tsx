import React, { useEffect } from "react";
import { Tabs, Tab, Box, Alert, AlertTitle, Typography } from "@mui/material";
import BucketsMain from "./BucketsMain";
import FtsIndexes from "./FtsIndexes";
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

  // Get bucket store data (single-tenant mode)
  const bucketStore = useBucketStore();
  const bucket = bucketStore.bucket;

  // Load initial bucket data only (no refresh interval)
  useEffect(() => {
    if (!connection.isConnected) {
      return;
    }

    // Load initial data only once
    const loadInitialData = async () => {
      try {
        await bucketStore.fetchBucketData();
      } catch (error) {
        console.error("Failed to load initial bucket data:", error);
      }
    };

    loadInitialData();
  }, [connection.isConnected, bucketStore]);

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
          <Tab label="Collections" />
          <Tab disabled={!bucket} label="Samples" />
          <Tab disabled={!bucket} label="FTS Indexes" />
        </Tabs>

        <Box sx={{ display: "flex", alignItems: "center", gap: 2, pr: 2 }}>
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            FHIR Bucket:
          </Typography>
          <Typography
            variant="body2"
            sx={{ color: "primary.main", fontWeight: 600 }}
          >
            {bucket?.bucketName || "Loading..."}
          </Typography>
        </Box>
      </Box>

      {/* Tab Content */}
      <Box sx={{ flexGrow: 1, overflow: "hidden" }}>
        {selectedTab === 0 && <BucketsMain />}
        {selectedTab === 1 && <Samples />}
        {selectedTab === 2 && <FtsIndexes />}
      </Box>
    </Box>
  );
};

export default Buckets;
