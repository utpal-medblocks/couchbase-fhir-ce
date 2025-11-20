import { useState, useEffect } from "react";
import type { SelectChangeEvent } from "@mui/material";
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
  IconButton,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
  Alert,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  OutlinedInput,
  Tooltip,
  Snackbar,
  Tabs,
  Tab,
} from "@mui/material";
import {
  Add as AddIcon,
  Delete as DeleteIcon,
  ContentCopy as CopyIcon,
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
  AppRegistration as AppRegistrationIcon,
} from "@mui/icons-material";
import type { Token, GenerateTokenRequest } from "../../services/tokensService";
import {
  generateToken,
  getMyTokens,
  revokeToken,
} from "../../services/tokensService";

// Available FHIR scopes
const AVAILABLE_SCOPES = [
  "patient/*.read",
  "patient/*.write",
  "user/*.read",
  "user/*.write",
  "system/*.read",
  "system/*.write",
];

export default function Tokens() {
  const [tokens, setTokens] = useState<Token[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [generateDialogOpen, setGenerateDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedToken, setSelectedToken] = useState<Token | null>(null);

  // Results state
  const [newTokenJWT, setNewTokenJWT] = useState<string | null>(null);
  const [newClientCreds, setNewClientCreds] = useState<{
    clientId: string;
    clientSecret: string;
  } | null>(null);

  const [showToken, setShowToken] = useState(false);
  const [copySnackbar, setCopySnackbar] = useState(false);
  const [activeTab, setActiveTab] = useState(0);

  const [formData, setFormData] = useState<GenerateTokenRequest>({
    appName: "",
    scopes: [],
    type: "pat",
  });

  // Load tokens on mount
  useEffect(() => {
    loadTokens();
  }, []);

  const loadTokens = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getMyTokens();
      setTokens(data);
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to load tokens");
    } finally {
      setLoading(false);
    }
  };

  const handleGenerateToken = async () => {
    try {
      setError(null);
      const type = activeTab === 0 ? "pat" : "client";
      const response = await generateToken({ ...formData, type });

      if (type === "pat") {
        setSuccess(
          "Token generated successfully! Copy it now - it won't be shown again."
        );
        setNewTokenJWT(response.token || "");
      } else {
        setSuccess("App registered successfully! Copy the credentials now.");
        setNewClientCreds({
          clientId: response.clientId || "",
          clientSecret: response.clientSecret || "",
        });
      }

      setGenerateDialogOpen(false);
      resetForm();
      loadTokens();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to generate token");
    }
  };

  const handleRevokeToken = async () => {
    if (!selectedToken) return;

    try {
      setError(null);
      await revokeToken(selectedToken.id);
      setSuccess("Revoked successfully");
      setDeleteDialogOpen(false);
      setSelectedToken(null);
      loadTokens();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to revoke token");
    }
  };

  const handleScopeChange = (event: SelectChangeEvent<string[]>) => {
    const value = event.target.value;
    setFormData({
      ...formData,
      scopes: typeof value === "string" ? value.split(",") : value,
    });
  };

  const resetForm = () => {
    setFormData({
      appName: "",
      scopes: [],
      type: activeTab === 0 ? "pat" : "client",
    });
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopySnackbar(true);
  };

  const handleCloseResultsDialog = () => {
    setNewTokenJWT(null);
    setNewClientCreds(null);
    setShowToken(false);
  };

  const formatDate = (dateString?: string) => {
    if (!dateString) return "Never";
    return new Date(dateString).toLocaleString();
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case "active":
        return "success";
      case "revoked":
        return "error";
      case "expired":
        return "warning";
      default:
        return "default";
    }
  };

  const isExpired = (expiresAt: string) => {
    return new Date(expiresAt) < new Date();
  };

  const getTokenStatus = (token: Token) => {
    if (token.status === "revoked") return "revoked";
    if (isExpired(token.expiresAt)) return "expired";
    return "active";
  };

  const openGenerateDialog = () => {
    resetForm();
    setGenerateDialogOpen(true);
  };

  const filteredTokens = tokens.filter((t) => {
    if (activeTab === 0) return t.type === "pat" || !t.type; // Default to PAT for legacy
    return t.type === "client";
  });

  if (loading) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography>Loading...</Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ p: 3 }}>
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 3,
        }}
      >
        <Typography variant="h4">Developer Settings</Typography>
      </Box>

      {error && (
        <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {success && (
        <Alert
          severity="success"
          onClose={() => setSuccess(null)}
          sx={{ mb: 2 }}
        >
          {success}
        </Alert>
      )}

      <Box sx={{ borderBottom: 1, borderColor: "divider", mb: 3 }}>
        <Tabs value={activeTab} onChange={(_, val) => setActiveTab(val)}>
          <Tab label="Personal Access Tokens" />
          <Tab label="SMART Apps (Clients)" />
        </Tabs>
      </Box>

      <Card>
        <CardContent>
          <Box sx={{ display: "flex", justifyContent: "space-between", mb: 2 }}>
            <Box>
              <Typography variant="h6" gutterBottom>
                {activeTab === 0
                  ? "Personal Access Tokens"
                  : "Registered SMART Apps"}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {activeTab === 0
                  ? "Generate pre-authorized JWTs for testing APIs (e.g. Postman, Scripts)."
                  : "Register OAuth2 clients for your SMART on FHIR applications."}
              </Typography>
            </Box>
            <Button
              variant="contained"
              startIcon={
                activeTab === 0 ? <AddIcon /> : <AppRegistrationIcon />
              }
              onClick={openGenerateDialog}
            >
              {activeTab === 0 ? "Generate Token" : "Register App"}
            </Button>
          </Box>

          <TableContainer component={Paper}>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Name</TableCell>
                  <TableCell>
                    {activeTab === 1 ? "Client ID" : "Token ID"}
                  </TableCell>
                  <TableCell>Scopes</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Created</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredTokens.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={6} align="center">
                      No items found.{" "}
                      {activeTab === 0 ? "Generate a token" : "Register an app"}{" "}
                      to get started!
                    </TableCell>
                  </TableRow>
                ) : (
                  filteredTokens.map((token) => (
                    <TableRow key={token.id}>
                      <TableCell>{token.appName}</TableCell>
                      <TableCell sx={{ fontFamily: "monospace" }}>
                        {token.clientId}
                      </TableCell>
                      <TableCell>
                        <Box
                          sx={{ display: "flex", gap: 0.5, flexWrap: "wrap" }}
                        >
                          {token.scopes.map((scope) => (
                            <Chip key={scope} label={scope} size="small" />
                          ))}
                        </Box>
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={getTokenStatus(token)}
                          color={getStatusColor(getTokenStatus(token)) as any}
                          size="small"
                        />
                      </TableCell>
                      <TableCell>{formatDate(token.createdAt)}</TableCell>
                      <TableCell align="right">
                        <Tooltip title="Revoke">
                          <IconButton
                            onClick={() => {
                              setSelectedToken(token);
                              setDeleteDialogOpen(true);
                            }}
                            disabled={token.status === "revoked"}
                            color="error"
                          >
                            <DeleteIcon />
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>

      {/* Generate Dialog */}
      <Dialog
        open={generateDialogOpen}
        onClose={() => setGenerateDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>
          {activeTab === 0
            ? "Generate Personal Access Token"
            : "Register SMART Application"}
        </DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 2 }}>
            <TextField
              label="Application Name"
              value={formData.appName || ""}
              onChange={(e) =>
                setFormData({ ...formData, appName: e.target.value })
              }
              fullWidth
              required
              helperText="Descriptive name for identifying this token/app later."
            />
            <FormControl fullWidth required>
              <InputLabel>Scopes</InputLabel>
              <Select
                multiple
                value={formData.scopes}
                onChange={handleScopeChange}
                input={<OutlinedInput label="Scopes" />}
                renderValue={(selected) => (
                  <Box sx={{ display: "flex", flexWrap: "wrap", gap: 0.5 }}>
                    {selected.map((value) => (
                      <Chip key={value} label={value} size="small" />
                    ))}
                  </Box>
                )}
              >
                {AVAILABLE_SCOPES.map((scope) => (
                  <MenuItem key={scope} value={scope}>
                    {scope}
                  </MenuItem>
                ))}
              </Select>
            </FormControl>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setGenerateDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={handleGenerateToken}
            variant="contained"
            disabled={!formData.appName || formData.scopes.length === 0}
          >
            {activeTab === 0 ? "Generate Token" : "Register App"}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Results Dialog - Token (PAT) */}
      <Dialog
        open={!!newTokenJWT}
        onClose={handleCloseResultsDialog}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>Token Generated Successfully! 🎉</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            <strong>Important:</strong> Copy this token now! It won't be shown
            again.
          </Alert>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, mb: 2 }}>
            <TextField
              fullWidth
              multiline
              rows={4}
              value={newTokenJWT || ""}
              type={showToken ? "text" : "password"}
              InputProps={{
                readOnly: true,
                sx: { fontFamily: "monospace", fontSize: "0.875rem" },
              }}
            />
            <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
              <IconButton onClick={() => setShowToken(!showToken)}>
                {showToken ? <VisibilityOffIcon /> : <VisibilityIcon />}
              </IconButton>
              <IconButton onClick={() => handleCopy(newTokenJWT || "")}>
                <CopyIcon />
              </IconButton>
            </Box>
          </Box>
          <Typography variant="body2" color="text.secondary">
            Use this token as a Bearer token in your API requests.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseResultsDialog} variant="contained">
            Done
          </Button>
        </DialogActions>
      </Dialog>

      {/* Results Dialog - Client (App) */}
      <Dialog
        open={!!newClientCreds}
        onClose={handleCloseResultsDialog}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>App Registered Successfully! 🚀</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>
            <strong>Important:</strong> Copy the Client Secret now! It won't be
            shown again.
          </Alert>

          <Box sx={{ mb: 2 }}>
            <Typography variant="subtitle2" gutterBottom>
              Client ID
            </Typography>
            <Box sx={{ display: "flex", gap: 1 }}>
              <TextField
                fullWidth
                value={newClientCreds?.clientId}
                InputProps={{ readOnly: true, sx: { fontFamily: "monospace" } }}
                size="small"
              />
              <IconButton
                onClick={() => handleCopy(newClientCreds?.clientId || "")}
              >
                <CopyIcon />
              </IconButton>
            </Box>
          </Box>

          <Box sx={{ mb: 2 }}>
            <Typography variant="subtitle2" gutterBottom>
              Client Secret
            </Typography>
            <Box sx={{ display: "flex", gap: 1 }}>
              <TextField
                fullWidth
                value={newClientCreds?.clientSecret}
                type={showToken ? "text" : "password"}
                InputProps={{ readOnly: true, sx: { fontFamily: "monospace" } }}
                size="small"
              />
              <IconButton onClick={() => setShowToken(!showToken)}>
                {showToken ? <VisibilityOffIcon /> : <VisibilityIcon />}
              </IconButton>
              <IconButton
                onClick={() => handleCopy(newClientCreds?.clientSecret || "")}
              >
                <CopyIcon />
              </IconButton>
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseResultsDialog} variant="contained">
            Done
          </Button>
        </DialogActions>
      </Dialog>

      {/* Revoke Dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
      >
        <DialogTitle>Revoke Access?</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to revoke{" "}
            <strong>{selectedToken?.appName}</strong>? This will immediately
            invalidate{" "}
            {activeTab === 0 ? "the token" : "the application credentials"}.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleRevokeToken} color="error" variant="contained">
            Revoke
          </Button>
        </DialogActions>
      </Dialog>

      <Snackbar
        open={copySnackbar}
        autoHideDuration={2000}
        onClose={() => setCopySnackbar(false)}
        message="Copied to clipboard!"
      />
    </Box>
  );
}
