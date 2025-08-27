import React, { useEffect, useState } from "react";
import {
  Box,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  Button,
  Alert,
  Card,
  CardContent,
} from "@mui/material";

// Hooks and services
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import { tableHeaderStyle, tableCellStyle } from "../../styles/styles";
import { useTheme } from "@mui/material/styles";
import { blueGrey } from "@mui/material/colors";
import LinearProgressWithLabel from "../../components/LinearProgressWithLabel";
import ChipsArray from "../../components/ChipsArray";
import AddFhirBucketDialog from "./AddFhirBucketDialog";
import type { FhirConfiguration } from "../../store/bucketStore";

const DashboardCouchbaseServer: React.FC = () => {
  const { connection, metrics, metricsError, fetchMetrics } =
    useConnectionStore();
  const { fetchFhirConfig } = useBucketStore();

  // Dialog state
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedBucketName, setSelectedBucketName] = useState("");

  // Selected bucket and FHIR config state
  const [selectedBucket, setSelectedBucket] = useState<string | null>(null);
  const [fhirConfig, setFhirConfig] = useState<FhirConfiguration | null>(null);

  // Define borderStyle for use in Table sx prop
  const theme = useTheme();
  const borderColor =
    theme.palette.mode === "dark" ? blueGrey[800] : blueGrey[50];
  const borderStyle = `1px solid ${borderColor}`;

  // Poll metrics every 30 seconds when connected
  useEffect(() => {
    if (!connection.isConnected) {
      return;
    }

    // Fetch immediately
    fetchMetrics();

    // Set up polling interval
    const interval = setInterval(() => {
      fetchMetrics();
    }, 20000); // 20 seconds

    // Cleanup on unmount or connection change
    return () => {
      clearInterval(interval);
    };
  }, [connection.isConnected]);

  // Get data with fallbacks for no connection
  const nodes = metrics?.nodes || [];
  const buckets = metrics?.buckets || [];
  const clusterName = metrics?.clusterName || "No Connection";
  const clusterVersion = nodes.length > 0 ? nodes[0].version : "No Connection";
  const serviceQuotas = metrics?.serviceQuotas;
  const services = nodes.length > 0 ? nodes[0].services : [];

  // Handle FHIR bucket conversion
  const handleToggleFhir = (bucketName: string) => {
    setSelectedBucketName(bucketName);
    setDialogOpen(true);
  };

  // Handle successful conversion
  const handleConversionSuccess = () => {
    // Refresh metrics to show updated bucket status
    fetchMetrics();
    // Refresh FHIR buckets to show updated status
    setDialogOpen(false);
  };

  // Handle dialog close
  const handleDialogClose = () => {
    setDialogOpen(false);
    setSelectedBucketName("");
  };

  // Handle bucket row click
  const handleBucketClick = async (
    bucketName: string,
    isFhirBucket: boolean
  ) => {
    setSelectedBucket(bucketName);

    if (isFhirBucket && connection.name) {
      try {
        // Fetch FHIR config from API
        const config = await fetchFhirConfig(connection.name, bucketName);
        setFhirConfig(config);
      } catch (error) {
        console.error(`Failed to fetch FHIR config for ${bucketName}:`, error);
        setFhirConfig(null);
      }
    } else {
      setFhirConfig(null);
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
      {/* Cluster Overview */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Box>
          <Typography variant="h6">Cluster: {clusterName}</Typography>
          <Typography variant="body2" color="text.secondary">
            Version: {clusterVersion}
          </Typography>
        </Box>
        <Typography variant="body2" color="text.secondary">
          {nodes.length} node(s) • {buckets.length} bucket(s)
        </Typography>
      </Box>

      {/* Service Quotas and Services */}
      <Box sx={{ mb: 1 }}>
        {/* Service Quotas */}
        {serviceQuotas && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {(() => {
              const sq = serviceQuotas;
              const quotas = [
                (sq?.memoryQuota ?? 0) > 0 && `Data ${sq?.memoryQuota} MB`,
                (sq?.indexMemoryQuota ?? 0) > 0 &&
                  `Index ${sq?.indexMemoryQuota} MB`,
                (sq?.ftsMemoryQuota ?? 0) > 0 &&
                  `Search ${sq?.ftsMemoryQuota} MB`,
                (sq?.queryMemoryQuota ?? 0) > 0 &&
                  `Query ${sq?.queryMemoryQuota} MB`,
                (sq?.cbasMemoryQuota ?? 0) > 0 &&
                  `Analytics ${sq?.cbasMemoryQuota} MB`,
                (sq?.eventingMemoryQuota ?? 0) > 0 &&
                  `Eventing ${sq?.eventingMemoryQuota} MB`,
              ].filter(Boolean);
              return quotas.length > 0 ? `Quotas: ${quotas.join(" | ")}` : null;
            })()}
          </Typography>
        )}

        {/* Services */}
        <Box
          component="span"
          sx={{ display: "inline-flex", alignItems: "center" }}
        >
          <Typography variant="body2" color="text.secondary" sx={{ mr: 1 }}>
            Services:
          </Typography>
          <ChipsArray chipData={services} />
        </Box>
      </Box>

      {/* Error State */}
      {metricsError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          Failed to load metrics: {metricsError}
        </Alert>
      )}

      {/* Nodes Table */}
      <Typography variant="subtitle1" gutterBottom>
        Nodes
      </Typography>
      <TableContainer>
        <Table size="small" sx={{ border: borderStyle }}>
          <TableHead>
            <TableRow>
              <TableCell sx={tableHeaderStyle}>Hostname</TableCell>
              <TableCell sx={tableHeaderStyle}>Status</TableCell>
              <TableCell sx={tableHeaderStyle}>CPU</TableCell>
              <TableCell sx={tableHeaderStyle}>RAM</TableCell>
              <TableCell sx={tableHeaderStyle}>Services</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {nodes.length > 0 ? (
              nodes.map((node, index) => (
                <TableRow key={index}>
                  <TableCell sx={tableCellStyle}>{node.hostname}</TableCell>
                  <TableCell sx={tableCellStyle}>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                      <Box
                        sx={{
                          width: 8,
                          height: 8,
                          borderRadius: "50%",
                          backgroundColor:
                            node.status === "healthy"
                              ? "success.main"
                              : "error.main",
                        }}
                      />
                      {node.status}
                    </Box>
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    <LinearProgressWithLabel value={node.cpuUtilizationRate} />
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    <LinearProgressWithLabel value={node.ramUtilizationRate} />
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    <ChipsArray chipData={node.services} iconsOnly />
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={5} sx={tableCellStyle} align="center">
                  No nodes available
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Buckets Table */}
      <Typography variant="subtitle1" gutterBottom>
        Buckets
      </Typography>
      <TableContainer>
        <Table size="small" sx={{ border: borderStyle }}>
          <TableHead>
            <TableRow>
              <TableCell sx={tableHeaderStyle}>Name</TableCell>
              <TableCell sx={tableHeaderStyle}>RAM Used</TableCell>
              <TableCell sx={tableHeaderStyle}>Disk Used</TableCell>
              <TableCell sx={tableHeaderStyle}>Items</TableCell>
              <TableCell sx={tableHeaderStyle}>Ops/Sec</TableCell>
              <TableCell sx={tableHeaderStyle}>Status</TableCell>
              <TableCell sx={tableHeaderStyle}>FHIR</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {buckets.length > 0 ? (
              buckets.map((bucket, index) => (
                <TableRow
                  key={index}
                  hover
                  onClick={() =>
                    handleBucketClick(bucket.name, bucket.isFhirBucket || false)
                  }
                  sx={{
                    cursor: "pointer",
                    backgroundColor:
                      selectedBucket === bucket.name
                        ? "action.selected"
                        : "inherit",
                  }}
                >
                  <TableCell sx={tableCellStyle}>{bucket.name}</TableCell>
                  <TableCell sx={tableCellStyle}>
                    {bucket.ramUsed} MB / {bucket.ramQuota} MB
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    {(bucket.diskUsed / 1024 / 1024).toFixed(0)} MB
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    {bucket.itemCount.toLocaleString()}
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    {bucket.opsPerSec.toFixed(1)}
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
                      <Box
                        sx={{
                          width: 8,
                          height: 8,
                          borderRadius: "50%",
                          backgroundColor:
                            bucket.status === "Ready"
                              ? "success.main"
                              : "warning.main",
                        }}
                      />
                      {bucket.status || "Ready"}
                    </Box>
                  </TableCell>
                  <TableCell sx={tableCellStyle}>
                    {bucket.isFhirBucket ? (
                      <Typography
                        variant="body2"
                        sx={{
                          padding: "4px 12px",
                          backgroundColor: "success.main",
                          color: "success.contrastText",
                          borderRadius: 1,
                          display: "inline-block",
                          fontSize: "0.75rem",
                          fontWeight: "medium",
                        }}
                      >
                        FHIR
                      </Typography>
                    ) : (
                      <Button
                        size="small"
                        sx={{
                          textTransform: "none !important",
                          padding: "0px 10px !important",
                          marginX: "2px !important",
                        }}
                        onClick={(e) => {
                          e.stopPropagation(); // Prevent row click
                          handleToggleFhir(bucket.name);
                        }}
                      >
                        Add FHIR
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={7} sx={tableCellStyle} align="center">
                  No buckets available
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* FHIR Configuration Display */}
      {selectedBucket && (
        <Box sx={{ mt: 2 }}>
          <Typography variant="subtitle1" gutterBottom>
            FHIR Configuration for "{selectedBucket}"
          </Typography>

          {fhirConfig ? (
            <Box sx={{ display: "flex", gap: 2, flexWrap: "wrap" }}>
              {/* Left Box - Basic Configuration */}
              <Box sx={{ flex: 1, minWidth: "300px" }}>
                <Card variant="outlined" sx={{ height: "100%" }}>
                  <CardContent>
                    <Typography variant="h6" gutterBottom>
                      Basic Configuration
                    </Typography>

                    <Typography variant="body2" sx={{ mb: 0 }}>
                      <strong>FHIR Release:</strong> {fhirConfig.fhirRelease}
                    </Typography>

                    <Typography variant="body2" sx={{ mb: 0 }}>
                      <strong>Validation:</strong>
                      <ul style={{ margin: 0, paddingLeft: 20 }}>
                        <li>Mode: {fhirConfig.validation.mode}</li>
                        <li>
                          Profile:{" "}
                          {fhirConfig.validation.profile === "us-core"
                            ? "US Core 6.1.0"
                            : "none"}
                        </li>
                      </ul>
                    </Typography>
                  </CardContent>
                </Card>
              </Box>

              {/* Right Box - Logs Configuration */}
              <Box sx={{ flex: 1, minWidth: "300px" }}>
                <Card variant="outlined" sx={{ height: "100%" }}>
                  <CardContent>
                    <Typography variant="h6" gutterBottom>
                      Logs Configuration
                    </Typography>

                    <Typography variant="body2" sx={{ mb: 0 }}>
                      <strong>Logs:</strong>
                    </Typography>
                    <Box sx={{ ml: 2 }}>
                      <Typography variant="body2" color="text.secondary">
                        • Enable System Logs:{" "}
                        {fhirConfig.logs.enableSystem ? "Enabled" : "Disabled"}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        • Enable CRUD Audit:{" "}
                        {fhirConfig.logs.enableCRUDAudit
                          ? "Enabled"
                          : "Disabled"}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        • Enable Search Audit:{" "}
                        {fhirConfig.logs.enableSearchAudit
                          ? "Enabled"
                          : "Disabled"}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        • Rotation By: {fhirConfig.logs.rotationBy}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        • Number: {fhirConfig.logs.number}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        • S3 Endpoint: {fhirConfig.logs.s3Endpoint || "(none)"}
                      </Typography>
                    </Box>
                  </CardContent>
                </Card>
              </Box>
            </Box>
          ) : (
            <Card variant="outlined">
              <CardContent>
                <Typography
                  variant="body2"
                  color="text.secondary"
                  align="center"
                >
                  {buckets.find((b) => b.name === selectedBucket)?.isFhirBucket
                    ? "Loading FHIR configuration..."
                    : "This bucket is not FHIR-enabled. Click 'Add FHIR' to configure it."}
                </Typography>
              </CardContent>
            </Card>
          )}
        </Box>
      )}

      {/* FHIR Conversion Dialog */}
      <AddFhirBucketDialog
        open={dialogOpen}
        onClose={handleDialogClose}
        bucketName={selectedBucketName}
        connectionName={connection.name || "default"}
        onSuccess={handleConversionSuccess}
      />
    </Box>
  );
};

export default DashboardCouchbaseServer;
