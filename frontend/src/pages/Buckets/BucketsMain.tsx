import {
  Box,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  FormControl,
  Select,
  MenuItem,
  InputLabel,
  IconButton,
} from "@mui/material";
import { Refresh } from "@mui/icons-material";
import { useState, useCallback, useMemo, useEffect } from "react";
import type { SelectChangeEvent } from "@mui/material";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import { tableCellStyle, tableHeaderStyle } from "../../styles/styles";
import {
  TIME_RANGE_OPTIONS,
  type TimeRange,
} from "../../services/bucketMetricsService";
import BucketMetricsCharts from "../../components/BucketMetricsCharts";
import { getStoredTimeRange, storeTimeRange } from "../../utils/sessionStorage";

const BucketsMain = () => {
  // Get stores
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();

  // State for time range with session storage
  const [timeRange, setTimeRange] = useState<TimeRange>(() =>
    getStoredTimeRange("HOUR")
  );
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);

  const connectionId = connection.name;

  // Get bucket data
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const collections = bucketStore.collections[connectionId] || [];

  const handleTimeRangeChange = useCallback((event: SelectChangeEvent) => {
    const newTimeRange = event.target.value as TimeRange;
    setTimeRange(newTimeRange);
    storeTimeRange(newTimeRange);
  }, []);

  const handleRefresh = useCallback(() => {
    if (connectionId) {
      bucketStore
        .fetchBucketData(connectionId)
        .then(() => setLastRefreshed(new Date()))
        .catch(() => setLastRefreshed(new Date()));
    }
  }, [connectionId]);

  // Auto-refresh bucket data every 20 seconds to keep item counts fresh
  useEffect(() => {
    if (!connectionId) return;

    const refresh = () =>
      bucketStore
        .fetchBucketData(connectionId)
        .then(() => setLastRefreshed(new Date()))
        .catch(() => setLastRefreshed(new Date()));

    // Initial refresh
    refresh();

    // Interval refresh
    const interval = setInterval(refresh, 20000);

    return () => clearInterval(interval);
  }, [connectionId]);

  // Smart byte formatting function
  const formatBytes = useCallback((bytes: number): string => {
    if (bytes === 0) return "0 B";

    const units = ["B", "KB", "MB", "GB", "TB"];
    const k = 1024;
    const decimals = 1;

    const i = Math.floor(Math.log(bytes) / Math.log(k));
    const value = bytes / Math.pow(k, i);

    // For bytes, show whole numbers. For others, show 1 decimal place
    const formatted = i === 0 ? value.toString() : value.toFixed(decimals);

    return `${formatted} ${units[i]}`;
  }, []);

  // Filter collections for active bucket and Resources scope only
  const filteredCollections = collections.filter(
    (col) =>
      col.bucketName === activeBucket?.bucketName &&
      col.scopeName === "Resources"
  );

  // Sort collections with Patient first, then alphabetically
  const sortedCollections = useMemo(() => {
    return [...filteredCollections].sort((a, b) => {
      // Patient always comes first
      if (a.collectionName === "Patient") return -1;
      if (b.collectionName === "Patient") return 1;

      // Rest are sorted alphabetically
      return a.collectionName.localeCompare(b.collectionName);
    });
  }, [filteredCollections]);

  return (
    <Box sx={{ height: "100%", display: "flex", flexDirection: "column" }}>
      {/* Main Content - Split 50/50 */}
      <Box
        sx={{
          flex: 1,
          display: "flex",
          gap: 1,
          minHeight: 0,
          width: "100%",
        }}
      >
        {/* Collections Table - 50% */}
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
          {/* Collections Header with Refresh */}
          <Box
            sx={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              px: 1,
              py: 1,
              borderBottom: 0.5,
              borderColor: "divider",
            }}
          >
            <Typography variant="h6" component="div">
              Collections
            </Typography>
            <IconButton size="small" onClick={handleRefresh} sx={{ ml: 1 }}>
              <Refresh fontSize="small" />
            </IconButton>
          </Box>

          {activeBucket && (
            <TableContainer sx={{ height: "100%" }}>
              <Table size="small" stickyHeader>
                <TableHead>
                  <TableRow>
                    <TableCell sx={tableHeaderStyle}>Collection Name</TableCell>
                    <TableCell align="right" sx={tableHeaderStyle}>
                      Items
                    </TableCell>
                    <TableCell align="right" sx={tableHeaderStyle}>
                      Mem Used
                    </TableCell>
                    <TableCell align="right" sx={tableHeaderStyle}>
                      Disk Utilized
                    </TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {sortedCollections.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={4} align="center">
                        <Typography color="textSecondary">
                          No collections found
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ) : (
                    sortedCollections.map((collection) => (
                      <TableRow
                        key={`${collection.bucketName}-${collection.scopeName}-${collection.collectionName}`}
                      >
                        <TableCell sx={tableCellStyle}>
                          {collection.collectionName}
                        </TableCell>
                        <TableCell sx={tableCellStyle} align="right">
                          {collection.items.toLocaleString()}
                        </TableCell>
                        <TableCell sx={tableCellStyle} align="right">
                          {collection.memUsed > 0
                            ? formatBytes(collection.memUsed)
                            : "-"}
                        </TableCell>
                        <TableCell sx={tableCellStyle} align="right">
                          {collection.diskSize > 0
                            ? formatBytes(collection.diskSize)
                            : "-"}
                        </TableCell>
                      </TableRow>
                    ))
                  )}
                </TableBody>
              </Table>
              {/* Last Refreshed timestamp */}
              <Box sx={{ p: 1, borderTop: 0.5, borderColor: "divider" }}>
                <Typography variant="caption" color="text.secondary">
                  Last Refreshed:{" "}
                  {lastRefreshed ? lastRefreshed.toLocaleString() : "-"}
                </Typography>
              </Box>
            </TableContainer>
          )}
        </Box>

        {/* Metrics Charts - 50% */}
        <Box
          sx={{
            width: "50%",
            height: "100%",
            border: 1,
            borderColor: "divider",
            borderRadius: 1,
            overflow: "auto",
            display: "flex",
            flexDirection: "column",
          }}
        >
          {/* Metrics Header with Time Selector */}
          <Box
            sx={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              px: 1,
              py: 1,
              borderBottom: 0.5,
              borderColor: "divider",
            }}
          >
            <Typography variant="h6" component="div">
              Metrics
            </Typography>
            <FormControl size="small" sx={{ minWidth: 120 }}>
              <InputLabel>Range</InputLabel>
              <Select
                value={timeRange}
                onChange={(e) => {
                  handleTimeRangeChange(e);
                }}
                label="Range"
              >
                {TIME_RANGE_OPTIONS.map((option) => (
                  <MenuItem key={option.value} value={option.value}>
                    {option.label.replace("Last ", "")}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Box>

          <Box sx={{ flex: 1, p: 1 }}>
            {activeBucket ? (
              <BucketMetricsCharts
                connectionName={connection?.name || ""}
                bucketName={activeBucket.bucketName}
                timeRange={timeRange}
              />
            ) : (
              <Typography variant="body2" color="text.secondary">
                Select a bucket to view metrics
              </Typography>
            )}
          </Box>
        </Box>
      </Box>
    </Box>
  );
};

export default BucketsMain;
