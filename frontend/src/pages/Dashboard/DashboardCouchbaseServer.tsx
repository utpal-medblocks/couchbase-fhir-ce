import React, { useEffect, useState, useRef, useCallback } from "react";
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
  Divider,
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
import {
  fetchConfigSummary,
  type ConfigSummary,
  retryAutoConnect,
} from "../../services/configService";

const DashboardCouchbaseServer: React.FC = () => {
  const {
    connection,
    metrics,
    metricsError,
    fetchMetrics,
    error,
    backendReady,
  } = useConnectionStore();
  const { fetchFhirConfig, getFhirConfig, bucket, fetchBucketData } =
    useBucketStore();

  // Local helper component for retry button with immediate polling
  const RetryAutoConnectButton: React.FC = () => {
    const { fetchConnection, backendReady } = useConnectionStore();
    const [busy, setBusy] = useState(false);
    const triesRef = useRef(0);

    const handleClick = useCallback(async () => {
      if (busy) return;
      if (!backendReady) return;
      setBusy(true);
      triesRef.current = 0;
      try {
        await retryAutoConnect();
        // Immediately poll a few times (every 1s up to 10s) to reflect status
        const poll = async () => {
          triesRef.current += 1;
          try {
            await fetchConnection();
          } catch {}
          if (
            triesRef.current < 10 &&
            !useConnectionStore.getState().connection.isConnected
          ) {
            setTimeout(poll, 1000);
          } else {
            setBusy(false);
          }
        };
        poll();
      } catch {
        setBusy(false);
      }
    }, [busy, backendReady, fetchConnection]);

    return (
      <Box sx={{ mt: 1 }}>
        <Button
          size="small"
          variant="outlined"
          disabled={busy || !backendReady}
          onClick={handleClick}
        >
          {busy ? "Connecting..." : "Retry Auto-Connect Now"}
        </Button>
      </Box>
    );
  };

  // Dialog state
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedBucketName, setSelectedBucketName] = useState("");

  // Track loading configs & local cache (bucketName -> config or null when fetched but absent)
  const [loadingConfigs, setLoadingConfigs] = useState<boolean>(false);
  const [bucketConfigs, setBucketConfigs] = useState<Record<string, any>>({});

  // Config summary for detailed connection banner
  const [configSummary, setConfigSummary] = useState<ConfigSummary | null>(
    null
  );
  const [loadingSummary, setLoadingSummary] = useState<boolean>(false);

  // Define borderStyle for use in Table sx prop
  const theme = useTheme();
  const borderColor =
    theme.palette.mode === "dark" ? blueGrey[800] : blueGrey[50];
  const borderStyle = `1px solid ${borderColor}`;

  // Fetch bucket data when connected
  useEffect(() => {
    if (connection.isConnected && !bucket) {
      fetchBucketData();
    }
  }, [connection.isConnected, bucket, fetchBucketData]);

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

  // Fetch config summary when backend is ready but not connected
  useEffect(() => {
    const shouldFetch = backendReady && !connection.isConnected;
    if (!shouldFetch) return;
    let cancelled = false;
    const run = async () => {
      try {
        setLoadingSummary(true);
        const summary = await fetchConfigSummary();
        if (!cancelled) setConfigSummary(summary);
      } catch {
        if (!cancelled) setConfigSummary(null);
      } finally {
        if (!cancelled) setLoadingSummary(false);
      }
    };
    run();
    return () => {
      cancelled = true;
    };
  }, [backendReady, connection.isConnected]);

  // Get data with fallbacks for no connection
  const nodes = metrics?.nodes || [];
  const buckets = metrics?.buckets || [];
  const clusterName = connection.name;
  const clusterVersion = nodes.length > 0 ? nodes[0].version : "No Connection";
  const serviceQuotas = metrics?.serviceQuotas;
  const services = nodes.length > 0 ? nodes[0].services : [];

  // Fetch all FHIR configs proactively (for buckets flagged isFhirBucket)
  useEffect(() => {
    const loadAll = async () => {
      if (!connection.connectionName || !buckets.length) return;
      setLoadingConfigs(true);
      try {
        // Single-tenant mode: only one bucket named "fhir"
        const fhirBuckets = buckets.filter((b: any) => b.name === "fhir");
        const results = await Promise.all(
          fhirBuckets.map(async (b: any) => {
            const cfg = await fetchFhirConfig();
            return { name: b.name, cfg };
          })
        );
        setBucketConfigs((prev) => {
          const next = { ...prev };
          results.forEach(({ name, cfg }) => {
            // cfg could be null (not configured yet)
            next[name] = cfg || null;
          });
          return next;
        });
      } finally {
        setLoadingConfigs(false);
      }
    };
    loadAll();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    buckets.map((b: any) => `${b.name}:${b.isFhirBucket}`).join("|"),
    connection.connectionName,
  ]);

  // If disconnected, render only the connection banner and guidance
  if (!connection.isConnected) {
    return (
      <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
        <Box sx={{ mb: 1 }}>
          <Alert severity="error" sx={{ py: 0.5 }}>
            {/* Main error message - separate from caption */}
            <Typography variant="body2" sx={{ fontWeight: "medium", mb: 0.5 }}>
              Connection Error
            </Typography>

            {/* Caption on its own line */}
            <Typography
              variant="caption"
              color="text.secondary"
              display="block"
            >
              Check your config.yaml file: {error || "Unable to connect"}. Also,
              check that the Couchbase server is up and reachable.
            </Typography>

            <Box sx={{ mt: 0.5 }}>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
              >
                config.yaml file:{" "}
                {loadingSummary
                  ? "loading..."
                  : configSummary?.success === false
                  ? configSummary?.message || "invalid"
                  : configSummary?.configExists
                  ? "successfully read"
                  : "not found"}
              </Typography>
              {configSummary?.connection && (
                <Box sx={{ mt: 0.5, pl: 1 }}>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    display="block"
                  >
                    connecting to:
                  </Typography>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    display="block"
                  >
                    - Server: {String(configSummary.connection.server ?? "")}
                  </Typography>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    display="block"
                  >
                    - Username:{" "}
                    {String(configSummary.connection.username ?? "")}
                  </Typography>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    display="block"
                  >
                    - Password:{" "}
                    {String(configSummary.connection.passwordMasked ?? "")}
                  </Typography>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    display="block"
                  >
                    - Server Type:{" "}
                    {String(configSummary.connection.serverType ?? "")}
                  </Typography>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    display="block"
                  >
                    - SSL Enabled:{" "}
                    {String(configSummary.connection.sslEnabled ?? "")}
                  </Typography>
                </Box>
              )}
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ mt: 0.5 }}
              >
                Backend is starting up and establishing connection...
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
              >
                <em>Checking every 20 seconds</em>
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ mt: 2, fontWeight: "medium" }}
              >
                If config.yaml needs correction:
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ mt: 0.5, pl: 1 }}
              >
                • Fix your original config.yaml (check indents!)
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ pl: 1 }}
              >
                • cd couchbase-fhir-ce
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ pl: 2 }}
              >
                • Fix the copied config.yaml in this folder (check indents!)
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ pl: 2 }}
              >
                • Click the Retry Auto-Connect Now button below
              </Typography>
              <RetryAutoConnectButton />
            </Box>
          </Alert>
        </Box>
      </Box>
    );
  }

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

      {/* FHIR Bucket Information - 3 Panels */}
      <Typography variant="subtitle1" gutterBottom sx={{ mt: 2 }}>
        FHIR Bucket
      </Typography>
      {(() => {
        // Use bucket from bucketStore (single-tenant: name === "fhir")
        // Also get metrics from the metrics buckets array
        const metricsData = buckets.find((b: any) => b.name === "fhir");
        const fhirConfig = bucket ? getFhirConfig() : null;

        if (!bucket) {
          return (
            <Alert severity="info">
              No FHIR bucket found. Please ensure a bucket named "fhir" is
              created and initialized.
            </Alert>
          );
        }

        return (
          <Box
            sx={{
              display: "flex",
              flexDirection: { xs: "column", md: "row" },
              gap: 1,
            }}
          >
            {/* Panel 1: FHIR Configuration */}
            <Box sx={{ flex: 1 }}>
              <Box sx={tableHeaderStyle}>
                <Typography
                  variant="subtitle2"
                  sx={{ m: 0, p: 0, lineHeight: 1, textAlign: "center" }}
                >
                  FHIR Configuration
                </Typography>
              </Box>
              <Card
                sx={{
                  height: "90%",
                  border: borderStyle,
                }}
              >
                <CardContent>
                  {fhirConfig ? (
                    <Box
                      sx={{
                        display: "flex",
                        flexDirection: "column",
                        gap: 0.5,
                      }}
                    >
                      <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                        <Box
                          component="span"
                          sx={{ color: "text.secondary", mr: 1 }}
                        >
                          Release:
                        </Box>
                        <Box component="span">
                          {fhirConfig.fhirRelease || "N/A"}
                        </Box>
                      </Typography>
                      <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                        <Box
                          component="span"
                          sx={{ color: "text.secondary", mr: 1 }}
                        >
                          Validation:
                        </Box>
                        <Box component="span">
                          {fhirConfig.validation?.mode || "N/A"}
                        </Box>
                      </Typography>
                      <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                        <Box
                          component="span"
                          sx={{ color: "text.secondary", mr: 1 }}
                        >
                          Profile:
                        </Box>
                        <Box component="span">
                          {fhirConfig.validation?.profile === "us-core" ? (
                            <>
                              <span role="img" aria-label="US Flag">
                                🇺🇸
                              </span>{" "}
                              us-core
                            </>
                          ) : (
                            fhirConfig.validation?.profile || "none"
                          )}
                        </Box>
                      </Typography>
                      <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                        <Box
                          component="span"
                          sx={{ color: "text.secondary", mr: 1 }}
                        >
                          Version:
                        </Box>
                        <Box component="span">
                          {fhirConfig.version || "N/A"}
                        </Box>
                      </Typography>
                      <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                        <Box
                          component="span"
                          sx={{ color: "text.secondary", mr: 1 }}
                        >
                          Created:
                        </Box>
                        <Box component="span">
                          {fhirConfig.createdAt || "N/A"}
                        </Box>
                      </Typography>
                    </Box>
                  ) : (
                    <Typography variant="body2" color="text.secondary">
                      {loadingConfigs
                        ? "Loading configuration..."
                        : "FHIR configuration not available"}
                    </Typography>
                  )}
                </CardContent>
              </Card>
            </Box>

            {/* Panel 2: Bucket Configuration */}
            <Box sx={{ flex: 1 }}>
              <Box sx={tableHeaderStyle}>
                <Typography
                  variant="subtitle2"
                  sx={{ m: 0, p: 0, lineHeight: 1, textAlign: "center" }}
                >
                  Bucket Configuration
                </Typography>
              </Box>

              <Card
                sx={{
                  height: "90%",
                  border: borderStyle,
                }}
              >
                <CardContent>
                  <Box
                    sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}
                  >
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Type:
                      </Box>
                      <Box component="span">{bucket.bucketType || "N/A"}</Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Storage:
                      </Box>
                      <Box component="span">
                        {bucket.storageBackend || "N/A"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Replicas:
                      </Box>
                      <Box component="span">
                        {bucket.replicaNumber ?? "N/A"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        RAM Quota:
                      </Box>
                      <Box component="span">
                        {bucket.ram
                          ? `${(bucket.ram / 1024 / 1024).toFixed(0)} MB`
                          : "N/A"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Durability:
                      </Box>
                      <Box component="span">
                        {bucket.durabilityMinLevel || "none"}
                      </Box>
                    </Typography>
                  </Box>
                </CardContent>
              </Card>
            </Box>

            {/* Panel 3: Metrics */}
            <Box sx={{ flex: 1 }}>
              <Box sx={tableHeaderStyle}>
                <Typography
                  variant="subtitle2"
                  sx={{ m: 0, p: 0, lineHeight: 1, textAlign: "center" }}
                >
                  Metrics
                </Typography>
              </Box>
              <Card
                sx={{
                  height: "90%",
                  border: borderStyle,
                }}
              >
                <CardContent>
                  <Box
                    sx={{ display: "flex", flexDirection: "column", gap: 0.5 }}
                  >
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Items:
                      </Box>
                      <Box component="span">
                        {bucket.itemCount?.toLocaleString() || "0"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Ops/sec:
                      </Box>
                      <Box component="span">
                        {bucket.opsPerSec?.toFixed(2) || "0"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Disk Used:
                      </Box>
                      <Box component="span">
                        {bucket.diskUsed
                          ? `${(bucket.diskUsed / 1024 / 1024).toFixed(2)} MB`
                          : "0 MB"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Quota Used:
                      </Box>
                      <Box component="span">
                        {bucket.quotaPercentUsed
                          ? `${bucket.quotaPercentUsed.toFixed(1)}%`
                          : "0%"}
                      </Box>
                    </Typography>
                    <Typography variant="body2" sx={{ lineHeight: 1.8 }}>
                      <Box
                        component="span"
                        sx={{ color: "text.secondary", mr: 1 }}
                      >
                        Resident %:
                      </Box>
                      <Box component="span">
                        {bucket.residentRatio
                          ? `${bucket.residentRatio.toFixed(1)}%`
                          : "0%"}
                      </Box>
                    </Typography>
                  </Box>
                </CardContent>
              </Card>
            </Box>
          </Box>
        );
      })()}

      {/* Detailed FHIR configuration panel removed per requirements */}

      {/* FHIR Conversion Dialog */}
      <AddFhirBucketDialog
        open={dialogOpen}
        onClose={handleDialogClose}
        bucketName={selectedBucketName}
        connectionName={connection.connectionName || "default"}
        onSuccess={handleConversionSuccess}
      />
    </Box>
  );
};

export default DashboardCouchbaseServer;
