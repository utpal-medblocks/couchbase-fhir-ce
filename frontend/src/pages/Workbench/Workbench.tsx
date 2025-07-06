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
import { useState, useEffect, useRef } from "react";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import EditorComponent from "../../components/EditorComponent";
import { useThemeContext } from "../../contexts/ThemeContext";
import WorkbenchConfigDialog from "./WorkbenchConfigDialog";
import { formatSize } from "../../utilities/formatSize";

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

// Remove hardcoded FHIR_ENDPOINTS - will be dynamic now

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

  // URL input ref for better performance
  const urlInputRef = useRef<HTMLInputElement>(null);

  // API request state
  const [request, setRequest] = useState<ApiRequest>({
    method: "GET",
    url: "/fhir/Patient", // Keep for initial value and selectFhirEndpoint updates
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
  const [selectedResponseTab, setSelectedResponseTab] = useState(0);

  // Get available buckets for FHIR
  const availableBuckets = bucketStore.buckets[connectionId] || [];

  // Get collections for selected bucket and "Resources" scope - same pattern as FhirResources.tsx
  const collections = bucketStore.collections[connectionId] || [];
  const filteredCollections = selectedBucket
    ? collections
        .filter(
          (col) =>
            col.bucketName === selectedBucket && col.scopeName === "Resources"
        )
        .sort((a, b) => {
          // Always put "Patient" first
          if (a.collectionName === "Patient") return -1;
          if (b.collectionName === "Patient") return 1;

          // Sort rest by items count in descending order
          return b.items - a.items;
        })
    : [];

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
      const currentUrl = urlInputRef.current?.value || request.url;
      const currentPath = currentUrl
        .replace(/^\/fhir\/[^\/]*\//, "/fhir/")
        .replace(/^\/fhir\//, "/fhir/");

      const newUrl = `/fhir/${selectedBucket}${
        currentPath.startsWith("/fhir/")
          ? currentPath.replace("/fhir", "")
          : currentPath
      }`;

      setRequest((prev) => ({
        ...prev,
        url: newUrl,
      }));

      // Update the input field value
      if (urlInputRef.current) {
        urlInputRef.current.value = newUrl;
      }
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
      // Get URL from ref (for better performance than controlled input)
      const currentUrl = urlInputRef.current?.value || request.url;

      // Build the full URL with query params
      let fullUrl = `http://${config.hostname}${currentUrl}`;
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

  const selectFhirEndpoint = (endpoint: string) => {
    const basePath = selectedBucket ? `/fhir/${selectedBucket}` : "/fhir";
    const newUrl = `${basePath}/${endpoint}`;

    // Update both state and ref for consistency
    setRequest((prev) => ({
      ...prev,
      url: newUrl,
    }));

    // Update the input field value
    if (urlInputRef.current) {
      urlInputRef.current.value = newUrl;
    }
  };

  const getStatusColor = (status: number) => {
    if (status >= 200 && status < 300) return "success";
    if (status >= 300 && status < 400) return "info";
    if (status >= 400 && status < 500) return "warning";
    return "error";
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
            }}
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

      {/* Main Content - Request and Response Side by Side */}
      <Box sx={{ flex: 1, display: "flex", gap: 2, minHeight: 0 }}>
        {/* Request Section - Left Side (50%) */}
        <Box sx={{ width: "50%", display: "flex", flexDirection: "column" }}>
          <Typography
            variant="body1"
            sx={{ mb: 0, fontWeight: "bold", fontSize: "1rem" }}
          >
            Request
          </Typography>
          <Box
            sx={{
              p: 1,
              flex: 1,
              display: "flex",
              flexDirection: "column",
              border: 1,
              borderColor: "divider",
              borderRadius: 1,
            }}
          >
            {/* URL Builder */}
            <Box sx={{ display: "flex", gap: 1, mb: 2, alignItems: "center" }}>
              <FormControl sx={{ minWidth: 100 }}>
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
                inputRef={urlInputRef}
                defaultValue={request.url}
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

            {/* FHIR Quick Actions - Dynamic Collections */}
            <Box sx={{ mb: 1 }}>
              <Typography variant="subtitle2" sx={{ mb: 1 }}>
                Quick FHIR Resources:
              </Typography>
              <Box
                sx={{
                  display: "flex",
                  gap: 0.5,
                  overflowX: "auto",
                  // Hide scrollbar for all browsers
                  scrollbarWidth: "none", // Firefox
                  msOverflowStyle: "none", // IE and Edge
                  "&::-webkit-scrollbar": {
                    display: "none", // Chrome, Safari, Opera
                  },
                  border: "1px solid gray",
                  p: 0.5,
                }}
              >
                {filteredCollections.length === 0 ? (
                  <Typography
                    variant="body2"
                    color="textSecondary"
                    sx={{ whiteSpace: "nowrap" }}
                  >
                    {selectedBucket
                      ? "No collections found"
                      : "Select a bucket"}
                  </Typography>
                ) : (
                  filteredCollections.map((collection) => (
                    <Chip
                      key={collection.collectionName}
                      label={`${collection.collectionName}`}
                      size="small"
                      onClick={() =>
                        selectFhirEndpoint(collection.collectionName)
                      }
                      sx={{
                        cursor: "pointer",
                        flexShrink: 0, // Prevent chips from shrinking
                      }}
                    />
                  ))
                )}
              </Box>
            </Box>

            {/* Request Details Tabs */}
            <Tabs
              value={selectedRequestTab}
              onChange={(_, newValue) => setSelectedRequestTab(newValue)}
              sx={{ borderBottom: 1, borderColor: "divider" }}
            >
              <Tab label="Params" />
              <Tab label="Auth" />
              <Tab label="Body" />
            </Tabs>

            {/* Tab Content */}
            <Box sx={{ flex: 1, overflow: "auto", mt: 2 }}>
              {/* Query Params Tab */}
              {selectedRequestTab === 0 && (
                <Box>
                  <Box
                    sx={{
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      mb: 1,
                    }}
                  >
                    <Typography variant="subtitle2">
                      Query Parameters
                    </Typography>
                    <Button
                      size="small"
                      startIcon={<AddIcon />}
                      onClick={addParam}
                    >
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
                                  fullWidth
                                  value={param.value}
                                  onChange={(e) =>
                                    updateParam(
                                      param.id,
                                      "value",
                                      e.target.value
                                    )
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
                <Box>
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

              {/* Body Tab */}
              {selectedRequestTab === 2 && (
                <Box sx={{ height: "100%" }}>
                  <TextField
                    fullWidth
                    multiline
                    rows={12}
                    value={request.body}
                    onChange={(e) =>
                      setRequest((prev) => ({ ...prev, body: e.target.value }))
                    }
                    placeholder="Enter request body (JSON)"
                    sx={{
                      "& .MuiInputBase-root": {
                        fontFamily: "monospace",
                        height: "100%",
                      },
                      "& .MuiInputBase-input": {
                        height: "100% !important",
                      },
                    }}
                  />
                </Box>
              )}
            </Box>
          </Box>
        </Box>

        {/* Response Section - Right Side (50%) */}
        <Box sx={{ width: "50%", display: "flex", flexDirection: "column" }}>
          <Box
            sx={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              mb: 1,
            }}
          >
            <Typography
              variant="body1"
              sx={{ mb: 0, fontSize: "1rem", fontWeight: "bold" }}
            >
              Response
            </Typography>
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
                  Size: {formatSize(response.responseSize)}
                </Typography>
              </Box>
            )}
          </Box>

          <Box
            sx={{
              flex: 1,
              overflow: "hidden",
              display: "flex",
              flexDirection: "column",
              border: 1,
              borderColor: "divider",
              borderRadius: 1,
            }}
          >
            {response ? (
              <>
                {/* Response Tabs */}
                <Tabs
                  value={selectedResponseTab}
                  onChange={(_, newValue) => setSelectedResponseTab(newValue)}
                  sx={{ borderBottom: 1, borderColor: "divider" }}
                >
                  <Tab label="Body" />
                  <Tab label="Headers" />
                </Tabs>

                {/* Response Content */}
                <Box sx={{ flex: 1, overflow: "hidden" }}>
                  {selectedResponseTab === 0 && (
                    <EditorComponent
                      value={JSON.stringify(response.data, null, 2)}
                      language="json"
                      theme={themeMode}
                    />
                  )}
                  {selectedResponseTab === 1 && (
                    <Box sx={{ p: 2 }}>
                      <TableContainer>
                        <Table size="small">
                          <TableHead>
                            <TableRow>
                              <TableCell>Header</TableCell>
                              <TableCell>Value</TableCell>
                            </TableRow>
                          </TableHead>
                          <TableBody>
                            {Object.entries(response.headers).map(
                              ([key, value]) => (
                                <TableRow key={key}>
                                  <TableCell sx={{ fontWeight: "bold" }}>
                                    {key}
                                  </TableCell>
                                  <TableCell>{value}</TableCell>
                                </TableRow>
                              )
                            )}
                          </TableBody>
                        </Table>
                      </TableContainer>
                    </Box>
                  )}
                </Box>
              </>
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
          </Box>
        </Box>
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
