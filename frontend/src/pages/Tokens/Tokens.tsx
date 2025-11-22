import { useState, useEffect } from "react";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
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
  Tooltip,
  Snackbar,
} from "@mui/material";
import {
  Add as AddIcon,
  Delete as DeleteIcon,
  ContentCopy as CopyIcon,
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
  Refresh as RefreshIcon,
  Block as BlockIcon,
} from "@mui/icons-material";
import type { Token, GenerateTokenRequest } from "../../services/tokensService";
import {
  generateToken,
  getMyTokens,
  revokeToken,
  deleteToken,
} from "../../services/tokensService";
import { useAuthStore } from "../../store/authStore";

export default function Tokens() {
  const { user } = useAuthStore();
  const [tokens, setTokens] = useState<Token[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [generateDialogOpen, setGenerateDialogOpen] = useState(false);
  const [revokeDialogOpen, setRevokeDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedToken, setSelectedToken] = useState<Token | null>(null);

  // Results state
  const [newTokenJWT, setNewTokenJWT] = useState<string | null>(null);
  const [showToken, setShowToken] = useState(false);
  const [copySnackbar, setCopySnackbar] = useState(false);

  const [formData, setFormData] = useState<GenerateTokenRequest>({
    appName: "",
    scopes: [],
    type: "pat",
  });

  const isAdmin = user?.role === "admin";

  // Load tokens on mount
  useEffect(() => {
    loadTokens();
  }, []);

  const loadTokens = async () => {
    try {
      setLoading(true);
      setError(null);
      // Backend automatically returns all tokens for admin, user's tokens for others
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
      // Use user's allowed scopes
      const response = await generateToken({
        appName: formData.appName,
        scopes: user?.allowedScopes || [],
        type: "pat",
      });

      setSuccess(
        "Token generated successfully! Copy it now - it won't be shown again."
      );
      setNewTokenJWT(response.token || "");
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
      setRevokeDialogOpen(false);
      setSelectedToken(null);
      loadTokens();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to revoke token");
    }
  };

  const handleDeleteToken = async () => {
    if (!selectedToken) return;

    try {
      setError(null);
      await deleteToken(selectedToken.id);
      setSuccess("Token permanently deleted");
      setDeleteDialogOpen(false);
      setSelectedToken(null);
      loadTokens();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to delete token");
    }
  };

  const resetForm = () => {
    setFormData({
      appName: "",
      scopes: [],
      type: "pat",
    });
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopySnackbar(true);
  };

  const handleCloseResultsDialog = () => {
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

  const openGenerateDialog = () => {
    resetForm();
    setGenerateDialogOpen(true);
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
          gap: 2,
        }}
      >
        <Typography variant="h6">API Tokens</Typography>
        <Box sx={{ display: "flex", gap: 2 }}>
          <Button
            variant="outlined"
            startIcon={<RefreshIcon />}
            onClick={loadTokens}
          >
            Refresh
          </Button>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={openGenerateDialog}
          >
            Generate Token
          </Button>
        </Box>
      </Box>

      {/* Alerts */}
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

      {/* Info Box - Scopes */}
      {/* {user?.allowedScopes && user.allowedScopes.length > 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          <Typography variant="body2" gutterBottom>
            {isAdmin ? "Your scopes (admin)" : "Your allowed scopes"}:
          </Typography>
          <Box sx={{ display: "flex", gap: 0.5, flexWrap: "wrap", mt: 1 }}>
            {user.allowedScopes.map((scope) => (
              <Chip key={scope} label={scope} size="small" />
            ))}
          </Box>
        </Alert>
      )} */}

      {/* Tokens Table */}
      <Card sx={{ width: "100%", minHeight: 200 }}>
        <CardContent>
          {loading ? (
            <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
              <CircularProgress />
            </Box>
          ) : tokens.length === 0 ? (
            <Box sx={{ textAlign: "center", p: 4 }}>
              <Typography variant="body1" color="text.secondary">
                No tokens found. Generate your first token to get started.
              </Typography>
            </Box>
          ) : (
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    {isAdmin && <TableCell>User</TableCell>}
                    <TableCell>Token ID</TableCell>
                    <TableCell>Scopes</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Created</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {tokens.map((token) => (
                    <TableRow key={token.id}>
                      <TableCell>{token.appName}</TableCell>
                      {isAdmin && <TableCell>{token.userId}</TableCell>}
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
                        <Tooltip
                          title={
                            token.status === "revoked"
                              ? "Already revoked"
                              : "Revoke token"
                          }
                        >
                          <span>
                            <IconButton
                              size="small"
                              onClick={() => {
                                setSelectedToken(token);
                                setRevokeDialogOpen(true);
                              }}
                              disabled={token.status === "revoked"}
                              color="warning"
                            >
                              <BlockIcon fontSize="small" />
                            </IconButton>
                          </span>
                        </Tooltip>
                        <Tooltip title="Permanently delete">
                          <IconButton
                            size="small"
                            onClick={() => {
                              setSelectedToken(token);
                              setDeleteDialogOpen(true);
                            }}
                          >
                            <DeleteIcon fontSize="small" />
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Generate Dialog */}
      <Dialog
        open={generateDialogOpen}
        onClose={() => setGenerateDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Generate API Token</DialogTitle>
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
              helperText="Descriptive name for identifying this token later (e.g., Postman, Python Script)."
            />
            <Alert severity="info">
              <Typography variant="body2">
                <strong>Scopes:</strong> This token will automatically have your
                allowed scopes:
              </Typography>
              <Box sx={{ mt: 1, display: "flex", gap: 0.5, flexWrap: "wrap" }}>
                {user?.allowedScopes && user.allowedScopes.length > 0 ? (
                  user.allowedScopes.map((scope) => (
                    <Chip key={scope} label={scope} size="small" />
                  ))
                ) : (
                  <Typography variant="caption" color="text.secondary">
                    No scopes assigned
                  </Typography>
                )}
              </Box>
            </Alert>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setGenerateDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={handleGenerateToken}
            variant="contained"
            disabled={!formData.appName}
          >
            Generate Token
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
            Use this token as a Bearer token in your API requests:
          </Typography>
          <Box
            sx={{
              mt: 1,
              p: 1,
              bgcolor: "default.main",
              borderRadius: 1,
              fontFamily: "monospace",
              fontSize: "0.75rem",
            }}
          >
            Authorization: Bearer {showToken ? newTokenJWT : "••••••••"}
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
        open={revokeDialogOpen}
        onClose={() => setRevokeDialogOpen(false)}
      >
        <DialogTitle>Revoke Token?</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to revoke{" "}
            <strong>{selectedToken?.appName}</strong>? This will immediately
            invalidate the token but keep it in the database. Revoked tokens
            cannot be reactivated.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRevokeDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={handleRevokeToken}
            color="warning"
            variant="contained"
          >
            Revoke
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete Dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
      >
        <DialogTitle>Permanently Delete Token</DialogTitle>
        <DialogContent>
          <Typography color="error" sx={{ mb: 1 }}>
            ⚠️ <strong>WARNING: This action cannot be undone!</strong>
          </Typography>
          <Typography>
            Are you sure you want to permanently delete{" "}
            <strong>{selectedToken?.appName}</strong>? This will remove all data
            from the system.
          </Typography>
          <Typography sx={{ mt: 2 }} color="text.secondary">
            Consider using "Revoke" instead to keep the token history.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDeleteToken} variant="contained">
            Permanently Delete
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
