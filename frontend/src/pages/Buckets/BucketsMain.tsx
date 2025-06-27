import { useEffect, useState } from "react";
import {
  Box,
  Button,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Typography,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  Stack,
  Card,
  CardContent,
  Alert,
  AlertTitle,
} from "@mui/material";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";

const BucketsMain = () => {
  // Get stores
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();

  const connectionId = connection.name;

  // Get bucket data
  const fhirBuckets = bucketStore.getFhirBuckets(connectionId);
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const activeScope = bucketStore.getActiveScope(connectionId);
  const collections = bucketStore.collections[connectionId] || [];

  // Log current state for debugging
  console.log(`🎯 BucketsMain render - Connection: ${connectionId}`);
  console.log(`🎯 FHIR Buckets: ${fhirBuckets.length}`, fhirBuckets);
  console.log(`🎯 Active Bucket:`, activeBucket);
  console.log(`🎯 Active Scope:`, activeScope);

  // Filter collections for active bucket and scope
  const filteredCollections = collections.filter(
    (col) =>
      col.bucketName === activeBucket?.bucketName &&
      col.scopeName === activeScope
  );

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
      // Fetch bucket data from backend - this will include isFhirBucket flags
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

  if (!connection.isConnected) {
    return (
      <Alert severity="warning">
        <AlertTitle>No Connection</AlertTitle>
        Please establish a connection first.
      </Alert>
    );
  }

  // Show empty state if no FHIR buckets found
  if (fhirBuckets.length === 0) {
    return (
      <Alert severity="info" sx={{ m: 2 }}>
        <AlertTitle>No FHIR Buckets Available</AlertTitle>
        No FHIR-enabled buckets found. Please ensure you have FHIR buckets
        configured.
        <Button
          variant="outlined"
          size="small"
          onClick={handleRefresh}
          sx={{ mt: 1, ml: 1 }}
        >
          Retry
        </Button>
      </Alert>
    );
  }

  return (
    <Box sx={{ p: 2 }}>
      {/* Header Controls */}
      <Stack direction={{ xs: "column", md: "row" }} spacing={2} sx={{ mb: 3 }}>
        <FormControl sx={{ minWidth: 200 }}>
          <InputLabel>FHIR Bucket</InputLabel>
          <Select
            value={activeBucket?.bucketName || ""}
            label="FHIR Bucket"
            onChange={(e) => handleBucketChange(e.target.value)}
          >
            {fhirBuckets.map((bucket) => (
              <MenuItem key={bucket.bucketName} value={bucket.bucketName}>
                {bucket.bucketName}
              </MenuItem>
            ))}
          </Select>
        </FormControl>

        <FormControl sx={{ minWidth: 200 }}>
          <InputLabel>Scope</InputLabel>
          <Select
            value={activeScope || ""}
            label="Scope"
            onChange={(e) => handleScopeChange(e.target.value)}
            disabled={!activeBucket}
          >
            <MenuItem value="Admin">Admin</MenuItem>
            <MenuItem value="Resources">Resources</MenuItem>
          </Select>
        </FormControl>

        <Button
          variant="outlined"
          onClick={handleRefresh}
          sx={{ minWidth: 120 }}
        >
          Refresh
        </Button>
      </Stack>

      {/* Collections Table */}
      {activeBucket && activeScope && (
        <>
          <Typography variant="h6" gutterBottom>
            Collections in {activeBucket.bucketName} - {activeScope}
          </Typography>

          <TableContainer component={Paper}>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Collection Name</TableCell>
                  <TableCell align="right">Items</TableCell>
                  <TableCell align="right">Disk Size (MB)</TableCell>
                  <TableCell align="right">Memory Used (MB)</TableCell>
                  <TableCell align="right">Operations/sec</TableCell>
                  <TableCell align="right">Indexes</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredCollections.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={6} align="center">
                      <Typography color="textSecondary">
                        No collections found
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  filteredCollections.map((collection) => (
                    <TableRow
                      key={`${collection.bucketName}-${collection.scopeName}-${collection.collectionName}`}
                    >
                      <TableCell component="th" scope="row">
                        {collection.collectionName}
                      </TableCell>
                      <TableCell align="right">
                        {collection.items.toLocaleString()}
                      </TableCell>
                      <TableCell align="right">
                        {(collection.diskSize / 1024 / 1024).toFixed(2)}
                      </TableCell>
                      <TableCell align="right">
                        {(collection.memUsed / 1024 / 1024).toFixed(2)}
                      </TableCell>
                      <TableCell align="right">
                        {collection.ops.toLocaleString()}
                      </TableCell>
                      <TableCell align="right">{collection.indexes}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </>
      )}

      {/* Bucket Details Footer */}
      {activeBucket && (
        <Card sx={{ mt: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom>
              Bucket Details: {activeBucket.bucketName}
            </Typography>
            <Box
              sx={{
                display: "grid",
                gridTemplateColumns: {
                  xs: "1fr",
                  sm: "repeat(2, 1fr)",
                  md: "repeat(4, 1fr)",
                },
                gap: 2,
              }}
            >
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Type
                </Typography>
                <Typography variant="body1">
                  {activeBucket.bucketType}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Items
                </Typography>
                <Typography variant="body1">
                  {activeBucket.itemCount.toLocaleString()}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Operations/sec
                </Typography>
                <Typography variant="body1">
                  {activeBucket.opsPerSec.toLocaleString()}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Quota Used
                </Typography>
                <Typography variant="body1">
                  {activeBucket.quotaPercentUsed.toFixed(1)}%
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  RAM (MB)
                </Typography>
                <Typography variant="body1">
                  {(activeBucket.ram / 1024 / 1024).toFixed(2)}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Disk Used (MB)
                </Typography>
                <Typography variant="body1">
                  {(activeBucket.diskUsed / 1024 / 1024).toFixed(2)}
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Resident Ratio
                </Typography>
                <Typography variant="body1">
                  {activeBucket.residentRatio.toFixed(1)}%
                </Typography>
              </Box>
              <Box>
                <Typography variant="body2" color="textSecondary">
                  Replicas
                </Typography>
                <Typography variant="body1">
                  {activeBucket.replicaNumber}
                </Typography>
              </Box>
            </Box>
          </CardContent>
        </Card>
      )}
    </Box>
  );
};

export default BucketsMain;
