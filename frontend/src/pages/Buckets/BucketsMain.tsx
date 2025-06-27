import {
  Box,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import { tableCellStyle, tableHeaderStyle } from "../../styles/styles";

const BucketsMain = () => {
  // Get stores
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();

  const connectionId = connection.name;

  // Get bucket data
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const activeScope = bucketStore.getActiveScope(connectionId);
  const collections = bucketStore.collections[connectionId] || [];

  // Log current state for debugging
  console.log(`🎯 BucketsMain render - Connection: ${connectionId}`);
  console.log(`🎯 Active Bucket:`, activeBucket);
  console.log(`🎯 Active Scope:`, activeScope);
  console.log(`🎯 Collections: ${collections.length}`, collections);

  // Filter collections for active bucket and scope
  const filteredCollections = collections.filter(
    (col) =>
      col.bucketName === activeBucket?.bucketName &&
      col.scopeName === activeScope
  );

  console.log(
    `🎯 Filtered Collections: ${filteredCollections.length}`,
    filteredCollections
  );

  return (
    <Box sx={{ height: "100%", display: "flex", flexDirection: "column" }}>
      {/* Top Box - 65% height with Collections Table */}
      <Box
        sx={{
          height: "70%",
          border: 0.5,
          borderBottom: 0,
          borderColor: "divider",
          display: "flex",
          flexDirection: "column",
          overflow: "hidden", // Prevent the box itself from scrolling
        }}
      >
        {/* Collections Table Title */}
        <Box sx={{ px: 1, borderBottom: 0.5, borderColor: "divider" }}>
          <Typography variant="h6" component="div">
            Collections
          </Typography>
        </Box>

        {/* Scrollable Table Container */}
        {activeBucket && activeScope && (
          <TableContainer
            sx={{
              flexGrow: 1,
              overflow: "auto",
            }}
          >
            <Table size="small" aria-label="collections table" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Collection Name</TableCell>
                  <TableCell align="right" sx={tableHeaderStyle}>
                    Items
                  </TableCell>
                  <TableCell align="right" sx={tableHeaderStyle}>
                    Disk Size (MB)
                  </TableCell>
                  <TableCell align="right" sx={tableHeaderStyle}>
                    Memory Used (MB)
                  </TableCell>
                  <TableCell align="right" sx={tableHeaderStyle}>
                    Operations/sec
                  </TableCell>
                  <TableCell align="right" sx={tableHeaderStyle}>
                    Indexes
                  </TableCell>
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
                      <TableCell sx={tableCellStyle}>
                        {collection.collectionName}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {collection.items.toLocaleString()}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {(collection.diskSize / 1024 / 1024).toFixed(2)}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {(collection.memUsed / 1024 / 1024).toFixed(2)}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {collection.ops.toLocaleString()}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {collection.indexes}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Box>
      <Box
        sx={{
          height: "2%",
          //   border: 0.5,
          //   borderColor: "divider",
          //   borderTop: 0, // Remove top border to avoid double border
          display: "flex",
          overflow: "hidden", // Prevent the container from scrolling
        }}
      ></Box>
      {/* Bottom Box - 35% height with 3 Tables Side by Side */}
      <Box
        sx={{
          height: "28%",
          border: 0.5,
          borderColor: "divider",
          borderTop: 0, // Remove top border to avoid double border
          display: "flex",
          overflow: "hidden", // Prevent the container from scrolling
        }}
      >
        {/* Table 1 - Left */}
        <Box sx={{ flex: 1, borderRight: 1, borderColor: "divider" }}>
          <TableContainer sx={{ height: "100%", overflow: "auto" }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Configuration</TableCell>
                  <TableCell sx={tableHeaderStyle}></TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Type</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.bucketType || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Storage</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.storageBackend}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Replicas</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.replicaNumber || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Quota Used</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.quotaPercentUsed.toFixed(1) + "%" || "-"}
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
        </Box>

        {/* Table 2 - Middle */}
        <Box sx={{ flex: 1, borderRight: 1, borderColor: "divider" }}>
          <TableContainer sx={{ height: "100%", overflow: "auto" }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Setting</TableCell>
                  <TableCell sx={tableHeaderStyle}></TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Eviction Policy</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.evictionPolicy || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Durability</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.durabilityMinLevel || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Conflict Resolution</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.conflictResolutionType || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>TTL</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.maxTTL || "0"}
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
        </Box>

        {/* Table 3 - Right */}
        <Box sx={{ flex: 1 }}>
          <TableContainer sx={{ height: "100%", overflow: "auto" }}>
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Performance</TableCell>
                  <TableCell sx={tableHeaderStyle}></TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Ops/sec</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.opsPerSec || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Disk Used</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.diskUsed
                      ? (activeBucket.diskUsed / 1024 / 1024).toFixed(2) + " MB"
                      : "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Resident %</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.residentRatio || "-"}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell sx={tableCellStyle}>Cache Hit %</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {activeBucket?.cacheHit || "-"}
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </TableContainer>
        </Box>
      </Box>
    </Box>
  );
};

export default BucketsMain;
