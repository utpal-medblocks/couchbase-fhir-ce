import {
  Box,
  Typography,
  FormControl,
  Select,
  MenuItem,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  CircularProgress,
  Chip,
  IconButton,
} from "@mui/material";
import { useState, useEffect } from "react";
import { tableHeaderStyle, tableCellStyle } from "../../styles/styles";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import { useFtsIndexStore } from "../../store/ftsIndexStore";
import type { FtsIndexDetails } from "../../store/ftsIndexStore";
import EditorComponent from "../../components/EditorComponent";
import FTSIndexTreeDisplay from "./FtsIndexTreeDisplay";
import FtsMetricsCharts from "../../components/FtsMetricsCharts";
import { useThemeContext } from "../../contexts/ThemeContext";
import RefreshIcon from "@mui/icons-material/Refresh";

export default function FTSIndexes() {
  // Get stores and theme
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();
  const ftsIndexStore = useFtsIndexStore();
  const { themeMode } = useThemeContext();

  const connectionId = connection.name;

  // State for component
  const [selectedBucket, setSelectedBucket] = useState("");
  const [selectedScope, setSelectedScope] = useState("Resources");
  const [selectedTab, setSelectedTab] = useState(0);
  const [selectedIndex, setSelectedIndex] = useState<FtsIndexDetails | null>(
    null
  );

  // Get available buckets for FHIR
  const availableBuckets = bucketStore.buckets[connectionId] || [];

  // Get FTS indexes from store
  const { indexes, loading, error } = ftsIndexStore;

  // Set default bucket if none selected and buckets are available
  useEffect(() => {
    if (!selectedBucket && availableBuckets.length > 0) {
      // Find a FHIR bucket or use the first one
      const fhirBucket =
        availableBuckets.find(
          (bucket) =>
            bucket.bucketName.toLowerCase().includes("fhir") ||
            bucket.bucketName.toLowerCase().includes("health") ||
            bucket.bucketName.toLowerCase().includes("us-core")
        ) || availableBuckets[0];
      setSelectedBucket(fhirBucket.bucketName);
    }
  }, [availableBuckets, selectedBucket]);

  // Fetch bucket data on component mount if not already loaded
  useEffect(() => {
    const buckets = bucketStore.buckets[connectionId] || [];
    if (connectionId && buckets.length === 0) {
      bucketStore.fetchBucketData(connectionId);
    }
  }, [connectionId]);

  // Fetch FTS indexes when bucket and scope are selected
  useEffect(() => {
    if (connectionId && selectedBucket && selectedScope) {
      ftsIndexStore.fetchIndexes(connectionId, selectedBucket, selectedScope);
    }
  }, [connectionId, selectedBucket, selectedScope]);

  // Cleanup polling on unmount
  useEffect(() => {
    return () => {
      ftsIndexStore.clearIndexes();
    };
  }, []);

  const handleIndexClick = (index: FtsIndexDetails) => {
    setSelectedIndex(index);
    setSelectedTab(1); // Switch to Tree View tab when index is selected
  };

  const handleRefresh = () => {
    if (connectionId && selectedBucket && selectedScope) {
      ftsIndexStore.refreshIndexes(connectionId, selectedBucket, selectedScope);
    }
  };

  const formatBytes = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  };

  const formatLatency = (latency: number): string => {
    if (latency < 1) return `${(latency * 1000).toFixed(2)} μs`;
    if (latency < 1000) return `${latency.toFixed(2)} ms`;
    return `${(latency / 1000).toFixed(2)} s`;
  };

  return (
    <Box
      sx={{
        p: 1,
        height: "100%",
        display: "flex",
        flexDirection: "column",
        width: "100%",
      }}
    >
      {/* Header Section */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderBottom: 1,
          borderColor: "divider",
          pb: 2,
          mb: 2,
        }}
      >
        <Typography variant="h6">FTS Indexes</Typography>

        {/* Controls */}
        <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
          <FormControl size="small" sx={{ minWidth: 150 }}>
            <Select
              value={selectedBucket}
              onChange={(e) => setSelectedBucket(e.target.value)}
              displayEmpty
            >
              <MenuItem value="">
                <em>Select Bucket</em>
              </MenuItem>
              {availableBuckets.map((bucket) => (
                <MenuItem key={bucket.bucketName} value={bucket.bucketName}>
                  {bucket.bucketName}
                </MenuItem>
              ))}
            </Select>
          </FormControl>

          <FormControl size="small" sx={{ minWidth: 120 }}>
            <Select
              value={selectedScope}
              onChange={(e) => setSelectedScope(e.target.value)}
            >
              <MenuItem value="Resources">Resources</MenuItem>
              <MenuItem value="_default">_default</MenuItem>
            </Select>
          </FormControl>

          <IconButton onClick={handleRefresh} disabled={loading}>
            <RefreshIcon />
          </IconButton>
        </Box>
      </Box>

      {/* Main Content - Two Boxes */}
      <Box
        sx={{
          flex: 1,
          display: "flex",
          gap: 2,
          minHeight: 0,
          width: "100%",
        }}
      >
        {/* Index Table - 60% */}
        <Box
          sx={{
            width: "60%",
            height: "100%",
            border: 1,
            borderColor: "divider",
            borderRadius: 1,
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
          }}
        >
          {error && (
            <Box
              sx={{
                p: 2,
                backgroundColor: "error.light",
                color: "error.contrastText",
              }}
            >
              <Typography variant="body2">{error}</Typography>
            </Box>
          )}

          <TableContainer sx={{ height: "100%" }}>
            <Table stickyHeader size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Index Name</TableCell>
                  <TableCell sx={tableHeaderStyle}>Status</TableCell>
                  <TableCell sx={tableHeaderStyle}>Docs</TableCell>
                  <TableCell sx={tableHeaderStyle}>Last Used</TableCell>
                  <TableCell sx={tableHeaderStyle}>Latency</TableCell>
                  <TableCell sx={tableHeaderStyle}>Rate</TableCell>
                  <TableCell sx={tableHeaderStyle}>Queries</TableCell>
                  <TableCell sx={tableHeaderStyle}>Size</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={8} align="center">
                      <Box
                        sx={{ display: "flex", justifyContent: "center", p: 2 }}
                      >
                        <CircularProgress size={20} />
                        <Typography variant="body2" sx={{ ml: 1 }}>
                          Loading FTS indexes...
                        </Typography>
                      </Box>
                    </TableCell>
                  </TableRow>
                ) : !indexes || indexes.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} align="center">
                      <Typography color="textSecondary" variant="body2">
                        {selectedBucket && selectedScope
                          ? `No FTS indexes found for ${selectedBucket}/${selectedScope}`
                          : "Select a bucket and scope"}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  indexes.map((index) => (
                    <TableRow
                      key={index.indexName}
                      hover
                      onClick={() => handleIndexClick(index)}
                      sx={{
                        cursor: "pointer",
                        backgroundColor:
                          selectedIndex?.indexName === index.indexName
                            ? "action.selected"
                            : "inherit",
                      }}
                    >
                      <TableCell sx={tableCellStyle}>
                        {index.indexName}
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        <Chip
                          label={index.status}
                          size="small"
                          color={
                            index.status === "active" ? "success" : "default"
                          }
                        />
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {index.docsIndexed.toLocaleString()}
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        {index.lastTimeUsed}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {formatLatency(index.queryLatency)}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {index.queryRate.toFixed(1)}/s
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {index.totalQueries.toLocaleString()}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {formatBytes(index.diskSize)}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </Box>

        {/* Index Details Box - 40% */}
        <Box
          sx={{
            width: "40%",
            height: "100%",
            border: 1,
            borderColor: "divider",
            borderRadius: 1,
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
          }}
        >
          {/* Tabs */}
          <Box sx={{ borderBottom: 1, borderColor: "divider" }}>
            <Tabs
              value={selectedTab}
              onChange={(_, newValue) => setSelectedTab(newValue)}
            >
              <Tab label="JSON" />
              <Tab label="Tree View" />
              <Tab label="Metrics" />
            </Tabs>
          </Box>

          {/* Content */}
          <Box
            sx={{
              flex: 1,
              overflow: "hidden",
              display: "flex",
              flexDirection: "column",
            }}
          >
            {selectedTab === 0 && (
              <Box sx={{ flex: 1, overflow: "hidden" }}>
                {selectedIndex ? (
                  <EditorComponent
                    value={JSON.stringify(
                      selectedIndex.indexDefinition,
                      null,
                      2
                    )}
                    language="json"
                    theme={themeMode}
                  />
                ) : (
                  <Box
                    sx={{
                      display: "flex",
                      justifyContent: "center",
                      alignItems: "center",
                      height: "100%",
                    }}
                  >
                    <Typography variant="body2" color="text.secondary">
                      Select an FTS index to view JSON definition
                    </Typography>
                  </Box>
                )}
              </Box>
            )}

            {selectedTab === 1 && (
              <Box sx={{ flex: 1, overflow: "auto", px: 2, py: 0 }}>
                {selectedIndex ? (
                  <FTSIndexTreeDisplay
                    ftsIndexData={selectedIndex.indexDefinition}
                  />
                ) : (
                  <Box
                    sx={{
                      display: "flex",
                      justifyContent: "center",
                      alignItems: "center",
                      height: "100%",
                    }}
                  >
                    <Typography variant="body2" color="text.secondary">
                      Select an FTS index to view tree structure
                    </Typography>
                  </Box>
                )}
              </Box>
            )}

            {selectedTab === 2 && (
              <Box sx={{ flex: 1, overflow: "auto", p: 2 }}>
                {selectedIndex ? (
                  <FtsMetricsCharts
                    connectionName={connection?.name || ""}
                    bucketName={selectedIndex.bucketName}
                    indexName={selectedIndex.indexName}
                  />
                ) : (
                  <Box
                    sx={{
                      display: "flex",
                      justifyContent: "center",
                      alignItems: "center",
                      height: "100%",
                    }}
                  >
                    <Typography variant="body2" color="text.secondary">
                      Select an FTS index to view metrics
                    </Typography>
                  </Box>
                )}
              </Box>
            )}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
