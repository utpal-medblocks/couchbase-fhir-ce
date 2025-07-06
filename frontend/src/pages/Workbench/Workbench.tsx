import {
  Box,
  Typography,
  FormControl,
  Select,
  MenuItem,
  TextField,
  Button,
  Tabs,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  IconButton,
  CircularProgress,
} from "@mui/material";
import {
  Send as SendIcon,
  Add as AddIcon,
  Delete as DeleteIcon,
  Settings as SettingsIcon,
} from "@mui/icons-material";
import { useState, useEffect } from "react";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import EditorComponent from "../../components/EditorComponent";
import { useThemeContext } from "../../contexts/ThemeContext";
import WorkbenchConfigDialog from "./WorkbenchConfigDialog";

interface Header {
  id: string;
  key: string;
  value: string;
  enabled: boolean;
}

interface Param {
  id: string;
  key: string;
  value: string;
  enabled: boolean;
}

interface AuthConfig {
  type: "none" | "basic" | "bearer";
  username: string;
  password: string;
  token: string;
}

interface ApiRequest {
  method: string;
  url: string;
  params: Param[];
  headers: Header[];
  body: string;
  auth: AuthConfig;
}

interface ApiResponse {
  status: number;
  statusText: string;
  headers: Record<string, string>;
  data: any;
  responseTime: number;
  responseSize: number;
}

interface WorkbenchConfig {
  hostname: string;
  defaultTimeout: number;
  followRedirects: boolean;
}

const HTTP_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];

const FHIR_ENDPOINTS = [
  "Patient",
  "Observation",
  "Condition",
  "MedicationRequest",
  "Encounter",
  "DiagnosticReport",
  "Procedure",
  "AllergyIntolerance",
  "Immunization",
  "Organization",
  "Practitioner",
  "PractitionerRole",
  "Location",
  "metadata",
];

const Workbench = () => {
  // Get stores and theme
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();
  const { themeMode } = useThemeContext();

  const connectionId = connection.name;

  // Load config from session storage
  const loadConfig = (): WorkbenchConfig => {
    const stored = sessionStorage.getItem("workbench-config");
    if (stored) {
      return JSON.parse(stored);
    }
    return {
      hostname: "localhost:8080",
      defaultTimeout: 30000,
      followRedirects: true,
    };
  };

  // State for bucket selection (same as FhirResources.tsx)
  const [selectedBucket, setSelectedBucket] = useState("");

  // Configuration state
  const [config, setConfig] = useState<WorkbenchConfig>(loadConfig);
  const [showConfigDialog, setShowConfigDialog] = useState(false);

  // API request state
  const [request, setRequest] = useState<ApiRequest>({
    method: "GET",
    url: "/fhir/Patient",
    params: [],
    headers: [
      {
        id: "1",
        key: "Content-Type",
        value: "application/fhir+json",
        enabled: true,
      },
      { id: "2", key: "Accept", value: "application/fhir+json", enabled: true },
    ],
    body: "",
    auth: {
      type: "none",
      username: "",
      password: "",
      token: "",
    },
  });

  // API response state
  const [response, setResponse] = useState<ApiResponse | null>(null);
  const [loading, setLoading] = useState(false);

  // UI state
  const [selectedRequestTab, setSelectedRequestTab] = useState(0);

  // Get available buckets for FHIR
  const availableBuckets = bucketStore.buckets[connectionId] || [];

  // Set default bucket if none selected and buckets are available
  useEffect(() => {
    if (!selectedBucket && availableBuckets.length > 0) {
      const fhirBucket =
        availableBuckets.find(
          (bucket) =>
            bucket.bucketName.toLowerCase().includes("fhir") ||
            bucket.bucketName.toLowerCase().includes("health")
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
  }, [connectionId, bucketStore]);

  // Update URL when bucket changes
  useEffect(() => {
    if (selectedBucket) {
      const currentPath = request.url
        .replace(/^\/fhir\/[^\/]*\//, "/fhir/")
        .replace(/^\/fhir\//, "/fhir/");
      setRequest((prev) => ({
        ...prev,
        url: `/fhir/${selectedBucket}${
          currentPath.startsWith("/fhir/")
            ? currentPath.replace("/fhir", "")
            : currentPath
        }`,
      }));
    }
  }, [selectedBucket]);

  const handleConfigSave = (newConfig: WorkbenchConfig) => {
    sessionStorage.setItem("workbench-config", JSON.stringify(newConfig));
    setConfig(newConfig);
    setShowConfigDialog(false);
  };

  const handleSendRequest = async () => {
    if (!selectedBucket) {
      alert("Please select a FHIR bucket first");
      return;
    }

    setLoading(true);
    const startTime = Date.now();

    try {
      // Build the full URL with query params
      let fullUrl = `http://${config.hostname}${request.url}`;
      const enabledParams = request.params.filter(
        (p) => p.enabled && p.key && p.value
      );
      if (enabledParams.length > 0) {
        const queryString = enabledParams
          .map(
            (p) => `${encodeURIComponent(p.key)}=${encodeURIComponent(p.value)}`
          )
          .join("&");
        fullUrl += `?${queryString}`;
      }

      // Build headers object
      const headers: Record<string, string> = {};
      request.headers.forEach((header) => {
        if (header.enabled && header.key && header.value) {
          headers[header.key] = header.value;
        }
      });

      // Add auth headers
      if (
        request.auth.type === "basic" &&
        request.auth.username &&
        request.auth.password
      ) {
        headers["Authorization"] = `Basic ${btoa(
          `${request.auth.username}:${request.auth.password}`
        )}`;
      } else if (request.auth.type === "bearer" && request.auth.token) {
        headers["Authorization"] = `Bearer ${request.auth.token}`;
      }

      // Make the request (this is a placeholder - you'll need to implement the actual API call)
      console.log("Making request:", {
        method: request.method,
        url: fullUrl,
        headers,
        body: request.body,
      });

      // Simulate API response for now
      setTimeout(() => {
        const responseTime = Date.now() - startTime;
        const sampleData = {
          resourceType: "Bundle",
          id: "example-search-result",
          type: "searchset",
          total: 1,
          entry: [
            {
              fullUrl: `http://${config.hostname}/fhir/${selectedBucket}/Patient/example`,
              resource: {
                resourceType: "Patient",
                id: "example",
                name: [
                  {
                    use: "official",
                    family: "Doe",
                    given: ["John"],
                  },
                ],
              },
            },
          ],
        };
        const responseString = JSON.stringify(sampleData, null, 2);
        setResponse({
          status: 200,
          statusText: "OK",
          headers: {
            "Content-Type": "application/fhir+json",
            Date: new Date().toISOString(),
            "Content-Length": responseString.length.toString(),
          },
          data: sampleData,
          responseTime,
          responseSize: new Blob([responseString]).size,
        });
        setLoading(false);
      }, 1000);
    } catch (error) {
      console.error("Request failed:", error);
      const responseTime = Date.now() - startTime;
      setResponse({
        status: 500,
        statusText: "Internal Server Error",
        headers: {},
        data: { error: "Request failed" },
        responseTime,
        responseSize: 0,
      });
      setLoading(false);
    }
  };

  const addParam = () => {
    const newParam: Param = {
      id: Date.now().toString(),
      key: "",
      value: "",
      enabled: true,
    };
    setRequest((prev) => ({
      ...prev,
      params: [...prev.params, newParam],
    }));
  };

  const updateParam = (
    id: string,
    field: keyof Param,
    value: string | boolean
  ) => {
    setRequest((prev) => ({
      ...prev,
      params: prev.params.map((param) =>
        param.id === id ? { ...param, [field]: value } : param
      ),
    }));
  };

  const removeParam = (id: string) => {
    setRequest((prev) => ({
      ...prev,
      params: prev.params.filter((param) => param.id !== id),
    }));
  };

  const addHeader = () => {
    const newHeader: Header = {
      id: Date.now().toString(),
      key: "",
      value: "",
      enabled: true,
    };
    setRequest((prev) => ({
      ...prev,
      headers: [...prev.headers, newHeader],
    }));
  };

  const updateHeader = (
    id: string,
    field: keyof Header,
    value: string | boolean
  ) => {
    setRequest((prev) => ({
      ...prev,
      headers: prev.headers.map((header) =>
        header.id === id ? { ...header, [field]: value } : header
      ),
    }));
  };

  const removeHeader = (id: string) => {
    setRequest((prev) => ({
      ...prev,
      headers: prev.headers.filter((header) => header.id !== id),
    }));
  };

  const selectFhirEndpoint = (endpoint: string) => {
    const basePath = selectedBucket ? `/fhir/${selectedBucket}` : "/fhir";
    setRequest((prev) => ({
      ...prev,
      url: `${basePath}/${endpoint}`,
    }));
  };

  const getStatusColor = (status: number) => {
    if (status >= 200 && status < 300) return "success";
    if (status >= 300 && status < 400) return "info";
    if (status >= 400 && status < 500) return "warning";
    return "error";
  };

  const formatBytes = (bytes: number) => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
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
      {/* Header Section - Same as FhirResources.tsx */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderBottom: 1,
          borderColor: "divider",
          pb: 1,
          mb: 1,
        }}
      >
        <Typography variant="h6">FHIR API Workbench</Typography>
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, pr: 2 }}>
          <IconButton
            size="small"
            onClick={() => {
              setShowConfigDialog(true);
            }}
            sx={{ mr: 1 }}
          >
            <SettingsIcon />
          </IconButton>
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
              value={selectedBucket}
              onChange={(e) => setSelectedBucket(e.target.value)}
              displayEmpty
            >
              <MenuItem value="" disabled>
                Select FHIR Bucket
              </MenuItem>
              {availableBuckets.map((bucket) => (
                <MenuItem key={bucket.bucketName} value={bucket.bucketName}>
                  {bucket.bucketName}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
      </Box>

      {/* Request Builder */}
      <Box sx={{ mb: 1 }}>
        <Paper sx={{ p: 2 }}>
          {/* URL Builder */}
          <Box sx={{ display: "flex", gap: 1, mb: 2, alignItems: "center" }}>
            <FormControl size="small" sx={{ minWidth: 100 }}>
              <Select
                value={request.method}
                onChange={(e) =>
                  setRequest((prev) => ({ ...prev, method: e.target.value }))
                }
              >
                {HTTP_METHODS.map((method) => (
                  <MenuItem key={method} value={method}>
                    {method}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
            <TextField
              fullWidth
              size="small"
              value={request.url}
              onChange={(e) =>
                setRequest((prev) => ({ ...prev, url: e.target.value }))
              }
              placeholder="/fhir/Patient"
            />
            <Button
              variant="contained"
              onClick={handleSendRequest}
              disabled={loading || !selectedBucket}
              startIcon={
                loading ? <CircularProgress size={16} /> : <SendIcon />
              }
              sx={{ minWidth: 100 }}
            >
              {loading ? "Sending" : "Send"}
            </Button>
          </Box>

          {/* FHIR Quick Actions */}
          <Box sx={{ mb: 2 }}>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>
              Quick FHIR Endpoints:
            </Typography>
            <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}>
              {FHIR_ENDPOINTS.map((endpoint) => (
                <Chip
                  key={endpoint}
                  label={endpoint}
                  size="small"
                  onClick={() => selectFhirEndpoint(endpoint)}
                  sx={{ cursor: "pointer" }}
                />
              ))}
            </Box>
          </Box>

          {/* Request Details Tabs */}
          <Tabs
            value={selectedRequestTab}
            onChange={(_, newValue) => setSelectedRequestTab(newValue)}
            sx={{ borderBottom: 1, borderColor: "divider" }}
          >
            <Tab label="Params" sx={{ textTransform: "none" }} />
            <Tab label="Auth" sx={{ textTransform: "none" }} />
            <Tab label="Headers" sx={{ textTransform: "none" }} />
            <Tab label="Body" sx={{ textTransform: "none" }} />
          </Tabs>

          {/* Params Tab */}
          {selectedRequestTab === 0 && (
            <Box sx={{ mt: 2 }}>
              <Box
                sx={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  mb: 1,
                }}
              >
                <Typography variant="subtitle2">Query Parameters</Typography>
                <Button size="small" startIcon={<AddIcon />} onClick={addParam}>
                  Add Parameter
                </Button>
              </Box>
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell width="30px"></TableCell>
                      <TableCell>Key</TableCell>
                      <TableCell>Value</TableCell>
                      <TableCell width="50px">Action</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {request.params.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={4} align="center">
                          <Typography color="textSecondary" variant="body2">
                            No parameters added
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ) : (
                      request.params.map((param) => (
                        <TableRow key={param.id}>
                          <TableCell>
                            <input
                              type="checkbox"
                              checked={param.enabled}
                              onChange={(e) =>
                                updateParam(
                                  param.id,
                                  "enabled",
                                  e.target.checked
                                )
                              }
                            />
                          </TableCell>
                          <TableCell>
                            <TextField
                              size="small"
                              fullWidth
                              value={param.key}
                              onChange={(e) =>
                                updateParam(param.id, "key", e.target.value)
                              }
                              placeholder="Parameter name"
                            />
                          </TableCell>
                          <TableCell>
                            <TextField
                              size="small"
                              fullWidth
                              value={param.value}
                              onChange={(e) =>
                                updateParam(param.id, "value", e.target.value)
                              }
                              placeholder="Parameter value"
                            />
                          </TableCell>
                          <TableCell>
                            <IconButton
                              size="small"
                              onClick={() => removeParam(param.id)}
                            >
                              <DeleteIcon />
                            </IconButton>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </TableContainer>
            </Box>
          )}

          {/* Auth Tab */}
          {selectedRequestTab === 1 && (
            <Box sx={{ mt: 2 }}>
              <Typography variant="subtitle2" sx={{ mb: 2 }}>
                Authorization
              </Typography>
              <FormControl fullWidth sx={{ mb: 2 }}>
                <Select
                  value={request.auth.type}
                  onChange={(e) =>
                    setRequest((prev) => ({
                      ...prev,
                      auth: { ...prev.auth, type: e.target.value as any },
                    }))
                  }
                  size="small"
                >
                  <MenuItem value="none">No Auth</MenuItem>
                  <MenuItem value="basic">Basic Auth</MenuItem>
                  <MenuItem value="bearer">Bearer Token</MenuItem>
                </Select>
              </FormControl>

              {request.auth.type === "basic" && (
                <Box sx={{ display: "flex", gap: 2 }}>
                  <TextField
                    fullWidth
                    size="small"
                    label="Username"
                    value={request.auth.username}
                    onChange={(e) =>
                      setRequest((prev) => ({
                        ...prev,
                        auth: { ...prev.auth, username: e.target.value },
                      }))
                    }
                  />
                  <TextField
                    fullWidth
                    size="small"
                    label="Password"
                    type="password"
                    value={request.auth.password}
                    onChange={(e) =>
                      setRequest((prev) => ({
                        ...prev,
                        auth: { ...prev.auth, password: e.target.value },
                      }))
                    }
                  />
                </Box>
              )}

              {request.auth.type === "bearer" && (
                <TextField
                  fullWidth
                  size="small"
                  label="Token"
                  value={request.auth.token}
                  onChange={(e) =>
                    setRequest((prev) => ({
                      ...prev,
                      auth: { ...prev.auth, token: e.target.value },
                    }))
                  }
                />
              )}
            </Box>
          )}

          {/* Headers Tab */}
          {selectedRequestTab === 2 && (
            <Box sx={{ mt: 2 }}>
              <Box
                sx={{
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "center",
                  mb: 1,
                }}
              >
                <Typography variant="subtitle2">Headers</Typography>
                <Button
                  size="small"
                  startIcon={<AddIcon />}
                  onClick={addHeader}
                >
                  Add Header
                </Button>
              </Box>
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell width="30px"></TableCell>
                      <TableCell>Key</TableCell>
                      <TableCell>Value</TableCell>
                      <TableCell width="50px">Action</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {request.headers.map((header) => (
                      <TableRow key={header.id}>
                        <TableCell>
                          <input
                            type="checkbox"
                            checked={header.enabled}
                            onChange={(e) =>
                              updateHeader(
                                header.id,
                                "enabled",
                                e.target.checked
                              )
                            }
                          />
                        </TableCell>
                        <TableCell>
                          <TextField
                            size="small"
                            fullWidth
                            value={header.key}
                            onChange={(e) =>
                              updateHeader(header.id, "key", e.target.value)
                            }
                            placeholder="Header name"
                          />
                        </TableCell>
                        <TableCell>
                          <TextField
                            size="small"
                            fullWidth
                            value={header.value}
                            onChange={(e) =>
                              updateHeader(header.id, "value", e.target.value)
                            }
                            placeholder="Header value"
                          />
                        </TableCell>
                        <TableCell>
                          <IconButton
                            size="small"
                            onClick={() => removeHeader(header.id)}
                          >
                            <DeleteIcon />
                          </IconButton>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Box>
          )}

          {/* Body Tab */}
          {selectedRequestTab === 3 && (
            <Box sx={{ mt: 2, height: 200 }}>
              <TextField
                fullWidth
                multiline
                rows={8}
                value={request.body}
                onChange={(e) =>
                  setRequest((prev) => ({ ...prev, body: e.target.value }))
                }
                placeholder="Enter request body (JSON)"
                variant="outlined"
                sx={{
                  "& .MuiInputBase-root": {
                    fontFamily: "monospace",
                    fontSize: "0.875rem",
                  },
                }}
              />
            </Box>
          )}
        </Paper>
      </Box>

      {/* Response Section - Full width, simplified */}
      <Box sx={{ flex: 1, display: "flex", flexDirection: "column" }}>
        <Box
          sx={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            mb: 1,
          }}
        >
          <Typography variant="h6">Response</Typography>
          {response && (
            <Box sx={{ display: "flex", gap: 2, alignItems: "center" }}>
              <Chip
                label={`${response.status} ${response.statusText}`}
                color={getStatusColor(response.status)}
                size="small"
              />
              <Typography variant="body2" color="text.secondary">
                Time: {response.responseTime}ms
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Size: {formatBytes(response.responseSize)}
              </Typography>
            </Box>
          )}
        </Box>

        <Paper
          sx={{
            flex: 1,
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
          }}
        >
          {response ? (
            <Box sx={{ flex: 1, overflow: "hidden" }}>
              <EditorComponent
                value={JSON.stringify(response.data, null, 2)}
                language="json"
                theme={themeMode}
              />
            </Box>
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
                Send a request to see the response
              </Typography>
            </Box>
          )}
        </Paper>
      </Box>

      {/* Configuration Dialog */}
      <WorkbenchConfigDialog
        open={showConfigDialog}
        onClose={() => setShowConfigDialog(false)}
        onSave={handleConfigSave}
        initialConfig={config}
        selectedBucket={selectedBucket}
      />
    </Box>
  );
};

export default Workbench;
