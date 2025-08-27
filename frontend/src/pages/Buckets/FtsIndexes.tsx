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
import { useState, useEffect, useCallback, useMemo, useRef } from "react";
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
import {
  getStoredFtsIndex,
  storeFtsIndex,
  getStoredFtsTab,
  storeFtsTab,
} from "../../utils/sessionStorage";
import {
  formatIngestStatus,
  formatDocCount,
} from "../../services/ftsProgressService";
import React from "react";

// Constants
const SCOPE_NAME = "Resources"; // Always Resources
const PROGRESS_POLL_INTERVAL = 60000; // 1 minute

const FTSIndexes = React.memo(function FTSIndexes() {
  // Stores and context
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();
  const ftsIndexStore = useFtsIndexStore();
  const { themeMode } = useThemeContext();

  // Refs for cleanup
  const progressIntervalRef = useRef<ReturnType<typeof setInterval> | null>(
    null
  );
  const mountedRef = useRef(true);

  // Memoized store methods to prevent effect loops
  const fetchIndexes = useCallback(
    (connectionId: string, bucketName: string) => {
      return ftsIndexStore.fetchIndexes(connectionId, bucketName, SCOPE_NAME);
    },
    [ftsIndexStore.fetchIndexes]
  );

  const fetchProgress = useCallback(
    (connectionId: string, bucketName: string) => {
      return ftsIndexStore.fetchProgress(connectionId, bucketName, SCOPE_NAME);
    },
    [] // Remove dependency to prevent re-renders
  );

  // Component state
  const [selectedTab, setSelectedTab] = useState(() => getStoredFtsTab(0));
  const [selectedIndex, setSelectedIndex] = useState<FtsIndexDetails | null>(
    null
  );

  // Derived state
  const connectionId = connection?.name;
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const selectedBucket = activeBucket?.bucketName || "";
  const { indexes, loading, error, progressLoading } = ftsIndexStore;

  // Remove this console.log to prevent unnecessary re-renders

  // Sort indexes with ftsPatient first
  const sortedIndexes = useMemo(() => {
    if (!indexes) return null;
    return [...indexes].sort((a, b) => {
      if (a.indexName === "ftsPatient") return -1;
      if (b.indexName === "ftsPatient") return 1;
      return a.indexName.localeCompare(b.indexName);
    });
  }, [indexes]);

  // Main effect - handles fetching indexes and setting up progress polling
  useEffect(() => {
    let isCurrent = true;

    const setupData = async () => {
      if (!connectionId || !selectedBucket) {
        console.log("🚫 FTSIndexes: Missing connectionId or selectedBucket");
        return;
      }

      try {
        // console.log("🚀 FTSIndexes: Starting data setup");

        // Clear previous selection when bucket changes
        setSelectedIndex(null);

        // Fetch indexes
        await fetchIndexes(connectionId, selectedBucket);

        if (!isCurrent) return; // Component unmounted

        // Initial progress fetch
        // console.log("🚀 FTSIndexes: Starting initial progress fetch");
        await fetchProgress(connectionId, selectedBucket);

        if (!isCurrent) return; // Component unmounted

        // Set up progress polling
        // console.log("🔄 FTSIndexes: Setting up progress polling");
        progressIntervalRef.current = setInterval(() => {
          if (mountedRef.current) {
            // console.log("🔄 FTSIndexes: Polling progress update");
            fetchProgress(connectionId, selectedBucket);
          }
        }, PROGRESS_POLL_INTERVAL);
      } catch (error) {
        console.error("💥 FTSIndexes: Setup error:", error);
      }
    };

    setupData();

    return () => {
      isCurrent = false;
      if (progressIntervalRef.current) {
        // console.log("📋 FTSIndexes: Cleaning up progress interval");
        clearInterval(progressIntervalRef.current);
        progressIntervalRef.current = null;
      }
    };
  }, [connectionId, selectedBucket, fetchIndexes, fetchProgress]);

  // Auto-select index when indexes are loaded
  useEffect(() => {
    if (sortedIndexes && sortedIndexes.length > 0 && !selectedIndex) {
      const storedIndexName = getStoredFtsIndex();
      let indexToSelect: FtsIndexDetails | null = null;

      if (storedIndexName) {
        indexToSelect =
          sortedIndexes.find((idx) => idx.indexName === storedIndexName) ||
          null;
      }

      if (!indexToSelect) {
        indexToSelect = sortedIndexes[0];
      }

      if (indexToSelect) {
        // console.log(
        //   "🎯 FTSIndexes: Auto-selecting index:",
        //   indexToSelect.indexName
        // );
        setSelectedIndex(indexToSelect);
        storeFtsIndex(indexToSelect.indexName);
      }
    }
  }, [sortedIndexes]); // Removed selectedIndex from deps to avoid loops

  // Cleanup on unmount
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      ftsIndexStore.clearIndexes();
      if (progressIntervalRef.current) {
        clearInterval(progressIntervalRef.current);
      }
    };
  }, []);

  // Event handlers
  const handleIndexClick = useCallback((index: FtsIndexDetails) => {
    // console.log("👆 FTSIndexes: Index clicked:", index.indexName);
    setSelectedIndex(index);
    storeFtsIndex(index.indexName);
  }, []);

  const handleTabChange = useCallback((newValue: number) => {
    setSelectedTab(newValue);
    storeFtsTab(newValue);
  }, []);

  const handleRefresh = useCallback(async () => {
    if (connectionId && selectedBucket) {
      // console.log("🔄 FTSIndexes: Manual refresh triggered");
      await fetchIndexes(connectionId, selectedBucket);
      await fetchProgress(connectionId, selectedBucket);
    }
  }, [connectionId, selectedBucket, fetchIndexes, fetchProgress]);

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
        {/* Index Table - 50% */}
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
                  <TableCell sx={tableHeaderStyle}>Index Name</TableCell>
                  <TableCell sx={tableHeaderStyle} align="right">
                    Doc Count
                  </TableCell>
                  <TableCell sx={tableHeaderStyle}>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {loading ? (
                  <TableRow>
                    <TableCell colSpan={3} align="center">
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
                    <TableCell colSpan={3} align="center">
                      <Typography color="textSecondary" variant="body2">
                        {selectedBucket
                          ? `No FTS indexes found for ${selectedBucket}/${SCOPE_NAME}`
                          : "Select a bucket"}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  sortedIndexes!.map((index) => (
                    <TableRow
                      key={index.indexName} // Simplified key
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
                      <TableCell sx={tableCellStyle} align="right">
                        {index.progress
                          ? formatDocCount(index.progress.doc_count)
                          : progressLoading && !index.progress
                          ? "..."
                          : "-"}
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        {index.progress
                          ? formatIngestStatus(index.progress.ingest_status)
                          : progressLoading && !index.progress
                          ? "..."
                          : "-"}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </Box>

        {/* Index Details Box - 50% */}
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
              onChange={(_, newValue) => handleTabChange(newValue)}
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
});

export default FTSIndexes;
