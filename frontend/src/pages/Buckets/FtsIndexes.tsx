import {
  Box,
  Typography,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  CircularProgress,
  IconButton,
} from "@mui/material";
import { useState, useEffect, useCallback } from "react";
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
  const selectedScope = "Resources"; // Always Resources - no dropdown needed
  const [selectedTab, setSelectedTab] = useState(0);
  const [selectedIndex, setSelectedIndex] = useState<FtsIndexDetails | null>(
    null
  );

  // Get active bucket from store (no hardcoded dropdown)
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const selectedBucket = activeBucket?.bucketName || "";

  // Get FTS indexes from store
  const { indexes, loading, error } = ftsIndexStore;

  // No need to fetch bucket data - it's handled by parent Buckets.tsx

  // Fetch FTS indexes when bucket changes and clear selection
  useEffect(() => {
    if (connectionId && selectedBucket) {
      // Clear previous selection when bucket changes
      setSelectedIndex(null);
      setSelectedTab(0); // Reset to table view
      ftsIndexStore.fetchIndexes(connectionId, selectedBucket, selectedScope);
    }
  }, [connectionId, selectedBucket]);

  // Cleanup polling on unmount
  useEffect(() => {
    return () => {
      ftsIndexStore.clearIndexes();
    };
  }, []);

  const handleIndexClick = useCallback((index: FtsIndexDetails) => {
    setSelectedIndex(index);
    setSelectedTab(1); // Switch to Tree View tab when index is selected
  }, []);

  const handleRefresh = useCallback(() => {
    if (connectionId && selectedBucket) {
      ftsIndexStore.refreshIndexes(connectionId, selectedBucket, selectedScope);
    }
  }, [connectionId, selectedBucket, selectedScope]);

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
          px: 1,
          py: 0,
          borderBottom: 0.5,
          borderColor: "divider",
        }}
      >
        <Typography variant="h6">FTS Indexes</Typography>
        <IconButton onClick={handleRefresh} disabled={loading} size="small">
          <RefreshIcon />
        </IconButton>
      </Box>

      {/* Main Content - Two Boxes */}
      <Box
        sx={{
          flex: 1,
          display: "flex",
          gap: 1,
          minHeight: 0,
          width: "100%",
        }}
      >
        {/* Index Table - 60% */}
        <Box
          sx={{
            width: "50%",
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
                  <TableCell sx={tableHeaderStyle}>Bucket</TableCell>
                  <TableCell sx={tableHeaderStyle}>Index Name</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={2} align="center">
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
                    <TableCell colSpan={2} align="center">
                      <Typography color="textSecondary" variant="body2">
                        {selectedBucket
                          ? `No FTS indexes found for ${selectedBucket}/${selectedScope}`
                          : "Select a bucket"}
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
                        {index.bucketName}
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        {index.indexName}
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
            width: "50%",
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
