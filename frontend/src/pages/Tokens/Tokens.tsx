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
} from "@mui/material";
import {
  Add as AddIcon,
  Delete as DeleteIcon,
  ContentCopy as CopyIcon,
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
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
  const [newTokenJWT, setNewTokenJWT] = useState<string | null>(null);
  const [showToken, setShowToken] = useState(false);
  const [copySnackbar, setCopySnackbar] = useState(false);

  const [formData, setFormData] = useState<GenerateTokenRequest>({
    appName: "",
    scopes: [],
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
      const response = await generateToken(formData);
      setSuccess(
        "Token generated successfully! Copy it now - it won't be shown again."
      );
      setNewTokenJWT(response.token); // Show the actual JWT
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
      setSuccess("Token revoked successfully");
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
    });
  };

  const handleCopyToken = (token: string) => {
    navigator.clipboard.writeText(token);
    setCopySnackbar(true);
  };

  const handleCloseNewTokenDialog = () => {
    setNewTokenJWT(null);
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

  if (loading) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography>Loading tokens...</Typography>
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
        <Typography variant="h4">API Tokens</Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => setGenerateDialogOpen(true)}
        >
          Generate Token
        </Button>
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

      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Your Tokens
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Generate API tokens to access the FHIR API programmatically. Keep
            your tokens secure!
          </Typography>

          <TableContainer component={Paper}>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>App Name</TableCell>
                  <TableCell>Scopes</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Created</TableCell>
                  <TableCell>Expires</TableCell>
                  <TableCell>Last Used</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {tokens.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={7} align="center">
                      No tokens found. Generate one to get started!
                    </TableCell>
                  </TableRow>
                ) : (
                  tokens.map((token) => (
                    <TableRow key={token.id}>
                      <TableCell>{token.appName}</TableCell>
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
                      <TableCell>{formatDate(token.expiresAt)}</TableCell>
                      <TableCell>{formatDate(token.lastUsedAt)}</TableCell>
                      <TableCell align="right">
                        <Tooltip title="Revoke Token">
                          <IconButton
                            onClick={() => {
                              setSelectedToken(token);
                              setDeleteDialogOpen(true);
                            }}
                            disabled={token.status === "revoked"}
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

      {/* Generate Token Dialog */}
      <Dialog
        open={generateDialogOpen}
        onClose={() => setGenerateDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Generate New API Token</DialogTitle>
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
              helperText="Give your token a descriptive name (e.g., 'Mobile App', 'Testing')"
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
            color="primary"
            disabled={!formData.appName || formData.scopes.length === 0}
          >
            Generate Token
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
      >
        <DialogTitle>Revoke Token?</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to revoke the token for{" "}
            <strong>{selectedToken?.appName}</strong>? This action cannot be
            undone and the token will stop working immediately.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleRevokeToken} color="error" variant="contained">
            Revoke Token
          </Button>
        </DialogActions>
      </Dialog>

      {/* New Token Display Dialog */}
      <Dialog
        open={!!newTokenJWT}
        onClose={handleCloseNewTokenDialog}
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
              <Tooltip title={showToken ? "Hide Token" : "Show Token"}>
                <IconButton onClick={() => setShowToken(!showToken)}>
                  {showToken ? <VisibilityOffIcon /> : <VisibilityIcon />}
                </IconButton>
              </Tooltip>
              <Tooltip title="Copy to Clipboard">
                <IconButton onClick={() => handleCopyToken(newTokenJWT || "")}>
                  <CopyIcon />
                </IconButton>
              </Tooltip>
            </Box>
          </Box>
          <Typography variant="body2" color="text.secondary">
            Use this token as a Bearer token in your API requests:
          </Typography>
          <Paper
            sx={{
              p: 2,
              mt: 1,
              bgcolor: "grey.100",
              fontFamily: "monospace",
              fontSize: "0.875rem",
            }}
          >
            curl -H "Authorization: Bearer YOUR_TOKEN"
            http://localhost:8080/fhir/Patient
          </Paper>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseNewTokenDialog} variant="contained">
            I've Copied the Token
          </Button>
        </DialogActions>
      </Dialog>

      {/* Copy Snackbar */}
      <Snackbar
        open={copySnackbar}
        autoHideDuration={2000}
        onClose={() => setCopySnackbar(false)}
        message="Token copied to clipboard!"
      />
    </Box>
  );
}
