import React, { useState, useEffect } from "react";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormLabel,
  IconButton,
  Paper,
  ToggleButton,
  ToggleButtonGroup,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
  Alert,
  Checkbox,
  FormGroup,
  Tooltip,
  InputAdornment,
  Collapse,
  Divider,
  CircularProgress,
} from "@mui/material";
import {
  Add as AddIcon,
  Delete as DeleteIcon,
  ContentCopy as CopyIcon,
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
  Info as InfoIcon,
  ExpandMore as ExpandMoreIcon,
  ExpandLess as ExpandLessIcon,
  CheckCircle as CheckCircleIcon,
  Refresh as RefreshIcon,
} from "@mui/icons-material";
import { BsKey } from "react-icons/bs";
import type { OAuthClient } from "../../services/oauthClientService";
import {
  getAllClients,
  createClient as createClientAPI,
  revokeClient as revokeClientAPI,
} from "../../services/oauthClientService";

// OAuth Client interface (will be shared with backend later)
interface OAuthClientForm {
  clientId: string;
  clientSecret?: string;
  clientName: string;
  publisherUrl?: string;
  clientType: "patient" | "provider" | "system";
  authenticationType: "public" | "confidential";
  launchType: "standalone" | "ehr-launch";
  redirectUris: string[];
  scopes: string[];
  pkceEnabled: boolean;
  pkceMethod: "S256" | "plain";
  status: "active" | "revoked";
  createdBy: string;
  createdAt: string;
  lastUsed?: string;
}

// Mandatory SMART scopes (cannot be removed)
const MANDATORY_SCOPES = [
  {
    value: "launch/patient",
    label: "launch/patient",
    description: "Patient context at launch",
  },
  {
    value: "openid",
    label: "openid",
    description: "OpenID Connect authentication",
  },
  { value: "fhirUser", label: "fhirUser", description: "User identity claim" },
  {
    value: "offline_access",
    label: "offline_access",
    description: "Refresh tokens for offline access",
  },
];

// US Core resource scopes for ONC certification (21 resources)
const US_CORE_RESOURCE_SCOPES = [
  "patient/Medication.rs",
  "patient/AllergyIntolerance.rs",
  "patient/CarePlan.rs",
  "patient/CareTeam.rs",
  "patient/Condition.rs",
  "patient/Device.rs",
  "patient/DiagnosticReport.rs",
  "patient/DocumentReference.rs",
  "patient/Encounter.rs",
  "patient/Goal.rs",
  "patient/Immunization.rs",
  "patient/Location.rs",
  "patient/MedicationRequest.rs",
  "patient/Observation.rs",
  "patient/Organization.rs",
  "patient/Patient.rs",
  "patient/Practitioner.rs",
  "patient/Procedure.rs",
  "patient/Provenance.rs",
  "patient/PractitionerRole.rs",
  "patient/RelatedPerson.rs",
];

// Additional optional scopes
const OPTIONAL_SCOPES = [
  {
    value: "profile",
    label: "profile",
    description: "User profile information",
  },
  {
    value: "user/*.read",
    label: "user/*.read",
    description: "Read user-accessible data",
  },
  {
    value: "user/*.write",
    label: "user/*.write",
    description: "Write user-accessible data",
  },
  {
    value: "system/*.read",
    label: "system/*.read",
    description: "Read all system data (backend)",
  },
  {
    value: "system/*.write",
    label: "system/*.write",
    description: "Write all system data (backend)",
  },
];

const ClientRegistration: React.FC = () => {
  // State
  const [clients, setClients] = useState<OAuthClient[]>([]);
  const [loading, setLoading] = useState(true);

  // Dialog states
  const [registerDialogOpen, setRegisterDialogOpen] = useState(false);
  const [createdClientDialogOpen, setCreatedClientDialogOpen] = useState(false);
  const [newlyCreatedClient, setNewlyCreatedClient] =
    useState<OAuthClient | null>(null);
  const [showClientSecret, setShowClientSecret] = useState(false);

  // Form state
  const [formData, setFormData] = useState({
    clientName: "",
    publisherUrl: "",
    clientType: "patient" as "patient" | "provider" | "system",
    authenticationType: "public" as "public" | "confidential",
    launchType: "standalone" as "standalone" | "ehr-launch",
    redirectUris: [] as string[],
    scopes: [
      "openid",
      "fhirUser",
      "launch/patient",
      "offline_access",
    ] as string[],
    pkceEnabled: true,
    pkceMethod: "S256" as "S256" | "plain",
  });

  const [redirectUriInput, setRedirectUriInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [usCoreExpanded, setUsCoreExpanded] = useState(false);
  const [usCoreEnabled, setUsCoreEnabled] = useState(true);
  const [selectedUsCoreScopes, setSelectedUsCoreScopes] = useState<string[]>(
    US_CORE_RESOURCE_SCOPES
  );

  // Load clients on mount
  useEffect(() => {
    loadClients();
  }, []);

  const loadClients = async () => {
    try {
      setLoading(true);
      setError(null);
      const fetchedClients = await getAllClients();
      setClients(fetchedClients);
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to load OAuth clients");
    } finally {
      setLoading(false);
    }
  };

  const handleOpenRegisterDialog = () => {
    resetForm();
    setRegisterDialogOpen(true);
  };

  const resetForm = () => {
    setFormData({
      clientName: "",
      publisherUrl: "",
      clientType: "patient",
      authenticationType: "public",
      launchType: "standalone",
      redirectUris: [],
      scopes: [
        ...MANDATORY_SCOPES.map((s) => s.value),
        ...US_CORE_RESOURCE_SCOPES,
      ],
      pkceEnabled: true,
      pkceMethod: "S256",
    });
    setRedirectUriInput("");
    setError(null);
    setUsCoreEnabled(true);
    setUsCoreExpanded(false);
    setSelectedUsCoreScopes(US_CORE_RESOURCE_SCOPES);
  };

  const handleAddRedirectUri = () => {
    if (!redirectUriInput.trim()) {
      setError("Redirect URI cannot be empty");
      return;
    }
    if (
      !redirectUriInput.startsWith("http://") &&
      !redirectUriInput.startsWith("https://")
    ) {
      setError("Redirect URI must start with http:// or https://");
      return;
    }
    if (formData.redirectUris.includes(redirectUriInput)) {
      setError("Redirect URI already added");
      return;
    }
    setFormData({
      ...formData,
      redirectUris: [...formData.redirectUris, redirectUriInput],
    });
    setRedirectUriInput("");
    setError(null);
  };

  const handleRemoveRedirectUri = (uri: string) => {
    setFormData({
      ...formData,
      redirectUris: formData.redirectUris.filter((u) => u !== uri),
    });
  };

  const handleOptionalScopeToggle = (scope: string) => {
    const currentScopes = formData.scopes;
    if (currentScopes.includes(scope)) {
      setFormData({
        ...formData,
        scopes: currentScopes.filter((s) => s !== scope),
      });
    } else {
      setFormData({
        ...formData,
        scopes: [...currentScopes, scope],
      });
    }
  };

  const handleUsCoreToggle = () => {
    const newUsCoreEnabled = !usCoreEnabled;
    setUsCoreEnabled(newUsCoreEnabled);

    if (newUsCoreEnabled) {
      // Add all selected US Core scopes
      const mandatoryScopes = MANDATORY_SCOPES.map((s) => s.value);
      const otherScopes = formData.scopes.filter(
        (s) =>
          !US_CORE_RESOURCE_SCOPES.includes(s) && !mandatoryScopes.includes(s)
      );
      setFormData({
        ...formData,
        scopes: [...mandatoryScopes, ...otherScopes, ...selectedUsCoreScopes],
      });
    } else {
      // Remove all US Core scopes
      setFormData({
        ...formData,
        scopes: formData.scopes.filter(
          (s) => !US_CORE_RESOURCE_SCOPES.includes(s)
        ),
      });
    }
  };

  const handleUsCoreResourceToggle = (resource: string) => {
    const newSelected = selectedUsCoreScopes.includes(resource)
      ? selectedUsCoreScopes.filter((s) => s !== resource)
      : [...selectedUsCoreScopes, resource];

    setSelectedUsCoreScopes(newSelected);

    // Update formData if US Core is enabled
    if (usCoreEnabled) {
      const mandatoryScopes = MANDATORY_SCOPES.map((s) => s.value);
      const otherScopes = formData.scopes.filter(
        (s) =>
          !US_CORE_RESOURCE_SCOPES.includes(s) && !mandatoryScopes.includes(s)
      );
      setFormData({
        ...formData,
        scopes: [...mandatoryScopes, ...otherScopes, ...newSelected],
      });
    }
  };

  const handleRegisterClient = async () => {
    // Validation
    if (!formData.clientName.trim()) {
      setError("App name is required");
      return;
    }
    if (formData.redirectUris.length === 0) {
      setError("At least one redirect URI is required");
      return;
    }
    if (formData.scopes.length === 0) {
      setError("At least one scope is required");
      return;
    }

    try {
      setError(null);

      // Call API to create client
      const newClient = await createClientAPI({
        clientName: formData.clientName,
        publisherUrl: formData.publisherUrl || undefined,
        clientType: formData.clientType,
        authenticationType: formData.authenticationType,
        launchType: formData.launchType,
        redirectUris: formData.redirectUris,
        scopes: formData.scopes,
        pkceEnabled: formData.pkceEnabled,
        pkceMethod: formData.pkceMethod,
      });

      // Show success dialog with credentials
      setNewlyCreatedClient(newClient);
      setRegisterDialogOpen(false);
      setCreatedClientDialogOpen(true);
      setSuccess("Client registered successfully");

      // Reload clients list
      loadClients();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to create OAuth client");
    }
  };

  const handleCopyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    // Could add a toast notification here
  };

  const handleRevokeClient = async (clientId: string) => {
    try {
      setError(null);
      await revokeClientAPI(clientId);
      setSuccess("Client revoked successfully");
      loadClients(); // Reload list
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to revoke OAuth client");
    }
  };

  return (
    <Box sx={{ p: 2, width: "100%" }}>
      {/* Header */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 2,
        }}
      >
        <Box>
          <Typography variant="h6">OAuth Client Registration</Typography>
          <Typography variant="caption" color="text.secondary">
            Register SMART on FHIR applications for OAuth 2.0 authentication
          </Typography>
        </Box>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={handleOpenRegisterDialog}
        >
          Register New App
        </Button>
      </Box>
      {/* Registered Clients Table */}
      <Card>
        <CardContent>
          {loading ? (
            <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
              <CircularProgress />
            </Box>
          ) : clients.length === 0 ? (
            <Box sx={{ textAlign: "center", py: 4 }}>
              <BsKey size={48} style={{ opacity: 0.3 }} />
              <Typography variant="body1" color="text.secondary" sx={{ mt: 2 }}>
                No registered clients yet
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Click "Register New App" to create your first OAuth client
              </Typography>
            </Box>
          ) : (
            <TableContainer component={Paper} elevation={0}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>App Name</TableCell>
                    <TableCell>Client ID</TableCell>
                    <TableCell>Type</TableCell>
                    <TableCell>Launch</TableCell>
                    <TableCell>Auth</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Created</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {clients.map((client) => (
                    <TableRow key={client.clientId}>
                      <TableCell>
                        <Typography variant="body2" fontWeight="medium">
                          {client.clientName}
                        </Typography>
                        {client.publisherUrl && (
                          <Typography variant="caption" color="text.secondary">
                            {client.publisherUrl}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography
                          variant="body2"
                          sx={{ fontFamily: "monospace", fontSize: "0.8rem" }}
                        >
                          {client.clientId}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Chip label={client.clientType} size="small" />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={client.launchType}
                          size="small"
                          variant="outlined"
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={client.authenticationType}
                          size="small"
                          color={
                            client.authenticationType === "confidential"
                              ? "warning"
                              : "default"
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={client.status}
                          size="small"
                          color={
                            client.status === "active" ? "success" : "default"
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption">
                          {new Date(client.createdAt).toLocaleDateString()}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        {client.status === "active" && (
                          <Button
                            size="small"
                            color="error"
                            onClick={() => handleRevokeClient(client.clientId)}
                          >
                            Revoke
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Register Client Dialog */}
      <Dialog
        open={registerDialogOpen}
        onClose={() => setRegisterDialogOpen(false)}
        maxWidth="md"
        fullWidth
        slotProps={{
          paper: {
            sx: { maxHeight: "90vh", backgroundColor: "default.background" },
          },
        }}
      >
        <DialogTitle sx={{ pb: 1 }}>Register New OAuth Client</DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
            {/* App Name */}
            <TextField
              label="App Name"
              value={formData.clientName}
              onChange={(e) =>
                setFormData({ ...formData, clientName: e.target.value })
              }
              fullWidth
              required
              helperText="User-friendly name for your application"
            />

            {/* Publisher URL */}
            <TextField
              label="App Publisher URL"
              value={formData.publisherUrl}
              onChange={(e) =>
                setFormData({ ...formData, publisherUrl: e.target.value })
              }
              fullWidth
              helperText="Optional: Your organization's website"
            />

            {/* Client Type */}
            <FormControl component="fieldset">
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <FormLabel component="legend" sx={{ mb: 0 }}>
                  Client Type
                </FormLabel>
                <ToggleButtonGroup
                  exclusive
                  color="primary"
                  size="small"
                  value={formData.clientType}
                  onChange={(_, newValue) => {
                    if (newValue) {
                      setFormData({ ...formData, clientType: newValue });
                    }
                  }}
                >
                  <ToggleButton value="patient" sx={{ textTransform: "none" }}>
                    Patient App
                  </ToggleButton>
                  <ToggleButton value="provider" sx={{ textTransform: "none" }}>
                    Provider App
                  </ToggleButton>
                  <ToggleButton value="system" sx={{ textTransform: "none" }}>
                    System/Backend
                  </ToggleButton>
                </ToggleButtonGroup>
              </Box>
              <Typography variant="caption" color="text.secondary">
                Patient: Standalone patient-facing apps | Provider:
                Clinician-facing apps | System: Backend services
              </Typography>
            </FormControl>

            {/* Launch Type */}
            <FormControl component="fieldset">
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <FormLabel component="legend" sx={{ mb: 0 }}>
                  Launch Type
                </FormLabel>
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  color="primary"
                  value={formData.launchType}
                  onChange={(_, newValue) => {
                    if (newValue) {
                      setFormData({ ...formData, launchType: newValue });
                    }
                  }}
                >
                  <ToggleButton
                    value="standalone"
                    sx={{ textTransform: "none" }}
                  >
                    Standalone Launch
                  </ToggleButton>
                  <ToggleButton
                    value="ehr-launch"
                    disabled
                    sx={{ textTransform: "none" }}
                  >
                    EHR Launch
                  </ToggleButton>
                </ToggleButtonGroup>
              </Box>
              <Typography variant="caption" color="text.secondary">
                Standalone: App launches independently | EHR Launch: Launched
                from within EHR (coming soon)
              </Typography>
            </FormControl>

            {/* Authentication Type */}
            <FormControl component="fieldset">
              <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                <FormLabel component="legend" sx={{ mb: 0 }}>
                  Authentication
                </FormLabel>
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  color="primary"
                  value={formData.authenticationType}
                  onChange={(_, newValue) => {
                    if (newValue) {
                      setFormData({
                        ...formData,
                        authenticationType: newValue,
                      });
                    }
                  }}
                >
                  <ToggleButton value="public" sx={{ textTransform: "none" }}>
                    Public Client
                  </ToggleButton>
                  <ToggleButton
                    value="confidential"
                    sx={{ textTransform: "none" }}
                  >
                    Confidential Client
                  </ToggleButton>
                </ToggleButtonGroup>
              </Box>
              <Typography variant="caption" color="text.secondary">
                Public: No client secret (mobile/browser apps) | Confidential:
                Uses client secret (server apps)
              </Typography>
            </FormControl>

            {/* PKCE Settings */}
            <Box>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={formData.pkceEnabled}
                    onChange={(e) =>
                      setFormData({
                        ...formData,
                        pkceEnabled: e.target.checked,
                      })
                    }
                  />
                }
                label="Enable PKCE (Proof Key for Code Exchange)"
              />
              {formData.pkceEnabled && (
                <FormControl component="fieldset" sx={{ ml: 4, mt: 0.5 }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                    <FormLabel component="legend" sx={{ mb: 0 }}>
                      PKCE Challenge Method
                    </FormLabel>
                    <ToggleButtonGroup
                      exclusive
                      size="small"
                      color="primary"
                      value={formData.pkceMethod}
                      onChange={(_, newValue) => {
                        if (newValue) {
                          setFormData({ ...formData, pkceMethod: newValue });
                        }
                      }}
                    >
                      <ToggleButton value="S256" sx={{ textTransform: "none" }}>
                        S256 (SHA-256)
                      </ToggleButton>
                      <ToggleButton
                        value="plain"
                        sx={{ textTransform: "none" }}
                      >
                        Plain
                      </ToggleButton>
                    </ToggleButtonGroup>
                  </Box>
                </FormControl>
              )}
            </Box>

            {/* Redirect URIs */}
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                Redirect URIs *
                <Tooltip title="URLs where the authorization server will redirect after authentication">
                  <IconButton size="small">
                    <InfoIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Typography>
              <Box sx={{ display: "flex", gap: 1, mb: 1 }}>
                <TextField
                  size="small"
                  fullWidth
                  value={redirectUriInput}
                  onChange={(e) => setRedirectUriInput(e.target.value)}
                  placeholder="https://example.com/callback"
                  onKeyPress={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      handleAddRedirectUri();
                    }
                  }}
                />
                <Button variant="outlined" onClick={handleAddRedirectUri}>
                  Add
                </Button>
              </Box>
              {formData.redirectUris.length > 0 && (
                <Paper variant="outlined" sx={{ p: 1 }}>
                  {formData.redirectUris.map((uri) => (
                    <Box
                      key={uri}
                      sx={{
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                        p: 1,
                      }}
                    >
                      <Typography
                        variant="body2"
                        sx={{ fontFamily: "monospace" }}
                      >
                        {uri}
                      </Typography>
                      <IconButton
                        size="small"
                        onClick={() => handleRemoveRedirectUri(uri)}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Box>
                  ))}
                </Paper>
              )}
            </Box>

            {/* Scopes */}
            <Box>
              <Typography
                variant="subtitle2"
                gutterBottom
                sx={{ fontWeight: 600 }}
              >
                Allowed Scopes *
              </Typography>

              {/* Mandatory Scopes - Chips (non-removable) */}
              <Box sx={{ mb: 2 }}>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  gutterBottom
                  display="block"
                >
                  Required Scopes (cannot be removed):
                </Typography>
                <Box
                  sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, mt: 1 }}
                >
                  {MANDATORY_SCOPES.map((scope) => (
                    <Chip
                      key={scope.value}
                      label={scope.value}
                      size="small"
                      color="primary"
                      icon={<CheckCircleIcon />}
                    />
                  ))}
                </Box>
              </Box>

              <Divider sx={{ my: 2 }} />

              {/* US Core Resources - Expandable Mega Chip */}
              {formData.clientType === "patient" && (
                <Box sx={{ mb: 2 }}>
                  <Box sx={{ display: "flex", alignItems: "center", gap: 2 }}>
                    <FormControlLabel
                      sx={{ m: 0 }}
                      control={
                        <Checkbox
                          size="small"
                          checked={usCoreEnabled}
                          onChange={handleUsCoreToggle}
                        />
                      }
                      label={
                        <Box
                          sx={{ display: "flex", alignItems: "center", gap: 1 }}
                        >
                          <Typography variant="body2" fontWeight="medium">
                            US Core Resources
                          </Typography>
                          <Chip
                            label={`${selectedUsCoreScopes.length}/${US_CORE_RESOURCE_SCOPES.length} resources`}
                            size="small"
                            variant="outlined"
                            color="info"
                          />
                        </Box>
                      }
                    />
                    {usCoreEnabled && (
                      <Button
                        size="small"
                        onClick={() => setUsCoreExpanded(!usCoreExpanded)}
                        endIcon={
                          usCoreExpanded ? (
                            <ExpandLessIcon />
                          ) : (
                            <ExpandMoreIcon />
                          )
                        }
                      >
                        {usCoreExpanded ? "Hide" : "Show"} Resources
                      </Button>
                    )}
                  </Box>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    sx={{ ml: 4, display: "block", mb: 1 }}
                  >
                    ONC-required FHIR resources for patient access
                  </Typography>
                  {usCoreEnabled && (
                    <Collapse in={usCoreExpanded}>
                      <Paper
                        variant="outlined"
                        sx={{
                          p: 2,
                          mt: 1,
                          maxHeight: 200,
                          overflow: "auto",
                          ml: 4,
                        }}
                      >
                        <FormGroup>
                          {US_CORE_RESOURCE_SCOPES.map((resource) => (
                            <FormControlLabel
                              key={resource}
                              control={
                                <Checkbox
                                  size="small"
                                  checked={selectedUsCoreScopes.includes(
                                    resource
                                  )}
                                  onChange={() =>
                                    handleUsCoreResourceToggle(resource)
                                  }
                                />
                              }
                              label={
                                <Typography
                                  variant="body2"
                                  sx={{
                                    fontFamily: "monospace",
                                    fontSize: "0.8rem",
                                  }}
                                >
                                  {resource}
                                </Typography>
                              }
                              sx={{ my: 0 }}
                            />
                          ))}
                        </FormGroup>
                      </Paper>
                    </Collapse>
                  )}
                </Box>
              )}

              {/* Optional Additional Scopes */}
              {(formData.clientType === "provider" ||
                formData.clientType === "system") && (
                <Box>
                  <Typography
                    variant="caption"
                    color="text.secondary"
                    gutterBottom
                    display="block"
                  >
                    Additional Scopes:
                  </Typography>
                  <FormGroup>
                    {OPTIONAL_SCOPES.map((scope) => (
                      <FormControlLabel
                        key={scope.value}
                        control={
                          <Checkbox
                            size="small"
                            checked={formData.scopes.includes(scope.value)}
                            onChange={() =>
                              handleOptionalScopeToggle(scope.value)
                            }
                          />
                        }
                        label={
                          <Box>
                            <Typography
                              variant="body2"
                              component="span"
                              sx={{
                                fontFamily: "monospace",
                                fontSize: "0.85rem",
                              }}
                            >
                              {scope.label}
                            </Typography>
                            <Typography
                              variant="caption"
                              color="text.secondary"
                              sx={{ ml: 1 }}
                            >
                              - {scope.description}
                            </Typography>
                          </Box>
                        }
                        sx={{ my: 0 }}
                      />
                    ))}
                  </FormGroup>
                </Box>
              )}
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRegisterDialogOpen(false)}>Cancel</Button>
          <Button variant="contained" onClick={handleRegisterClient}>
            Register Client
          </Button>
        </DialogActions>
      </Dialog>

      {/* Client Created Success Dialog */}
      <Dialog
        open={createdClientDialogOpen}
        onClose={() => setCreatedClientDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <BsKey size={24} />
            Client Registered Successfully
          </Box>
        </DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 3 }}>
            <Typography variant="body2" fontWeight="medium">
              Save these credentials now!
            </Typography>
            <Typography variant="caption">
              The client secret will not be shown again for security reasons.
            </Typography>
          </Alert>

          {newlyCreatedClient && (
            <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
              {/* Client ID */}
              <Box>
                <Typography
                  variant="subtitle2"
                  color="text.secondary"
                  gutterBottom
                >
                  Client ID
                </Typography>
                <TextField
                  fullWidth
                  value={newlyCreatedClient.clientId}
                  InputProps={{
                    readOnly: true,
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton
                          onClick={() =>
                            handleCopyToClipboard(newlyCreatedClient.clientId)
                          }
                        >
                          <CopyIcon />
                        </IconButton>
                      </InputAdornment>
                    ),
                  }}
                />
              </Box>

              {/* Client Secret (only for confidential clients) */}
              {newlyCreatedClient.clientSecret && (
                <Box>
                  <Typography
                    variant="subtitle2"
                    color="text.secondary"
                    gutterBottom
                  >
                    Client Secret
                  </Typography>
                  <TextField
                    fullWidth
                    type={showClientSecret ? "text" : "password"}
                    value={newlyCreatedClient.clientSecret}
                    InputProps={{
                      readOnly: true,
                      endAdornment: (
                        <InputAdornment position="end">
                          <IconButton
                            onClick={() =>
                              setShowClientSecret(!showClientSecret)
                            }
                          >
                            {showClientSecret ? (
                              <VisibilityOffIcon />
                            ) : (
                              <VisibilityIcon />
                            )}
                          </IconButton>
                          <IconButton
                            onClick={() =>
                              handleCopyToClipboard(
                                newlyCreatedClient.clientSecret!
                              )
                            }
                          >
                            <CopyIcon />
                          </IconButton>
                        </InputAdornment>
                      ),
                    }}
                  />
                </Box>
              )}

              {/* App Info */}
              <Paper
                variant="outlined"
                sx={{ p: 2, bgcolor: "background.default" }}
              >
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  App Name: <strong>{newlyCreatedClient.clientName}</strong>
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  Type: <strong>{newlyCreatedClient.clientType}</strong>
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  Authentication:{" "}
                  <strong>{newlyCreatedClient.authenticationType}</strong>
                </Typography>
                <Typography
                  variant="caption"
                  color="text.secondary"
                  display="block"
                >
                  PKCE:{" "}
                  <strong>
                    {newlyCreatedClient.pkceEnabled ? "Enabled" : "Disabled"}
                  </strong>
                  {newlyCreatedClient.pkceEnabled &&
                    ` (${newlyCreatedClient.pkceMethod})`}
                </Typography>
              </Paper>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button
            variant="contained"
            onClick={() => setCreatedClientDialogOpen(false)}
          >
            Done
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default ClientRegistration;
