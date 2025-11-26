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
  Avatar,
  MenuItem,
  Alert,
  CircularProgress,
  FormControl,
  InputLabel,
  Select,
  OutlinedInput,
  InputAdornment,
} from "@mui/material";
import {
  Add as AddIcon,
  Delete as DeleteIcon,
  Block as BlockIcon,
  CheckCircle as CheckCircleIcon,
  Refresh as RefreshIcon,
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
} from "@mui/icons-material";
import type {
  User,
  CreateUserRequest,
  UpdateUserRequest,
} from "../../services/usersService";
import {
  getAllUsers,
  createUser,
  updateUser,
  deleteUser,
  getUserInitials,
  formatRole,
  formatAuthMethod,
  formatStatus,
  getStatusColor,
} from "../../services/usersService";

// Scopes are now automatically assigned by the backend based on role
// admin: ["user/*.*", "system/*.*"]
// developer: ["user/*.*"]

const Users: React.FC = () => {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Dialog states
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [inactivateDialogOpen, setInactivateDialogOpen] = useState(false);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);

  // Form states
  const [formData, setFormData] = useState<Partial<CreateUserRequest>>({
    username: "",
    email: "",
    passwordHash: "",
    role: "developer",
    authMethod: "local",
  });

  // UI states
  const [createSubmitAttempted, setCreateSubmitAttempted] = useState(false);
  const [showCreatePassword, setShowCreatePassword] = useState(false);

  // Load users on mount
  useEffect(() => {
    loadUsers();
  }, []);

  const loadUsers = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getAllUsers();
      setUsers(data);
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to load users");
    } finally {
      setLoading(false);
    }
  };

  const handleCreateUser = async () => {
    try {
      setError(null);
      setCreateSubmitAttempted(true);

      // Validate required fields
      if (!formData.email || !formData.username) {
        setError("Email and username are required");
        return;
      }

      // Validate password (all users use local auth)
      if (!formData.passwordHash) {
        setError("Password is required");
        return;
      }

      // Use email as user ID
      const userRequest = {
        ...formData,
        id: formData.email,
      } as CreateUserRequest;
      await createUser(userRequest);
      setSuccess("User created successfully");
      setCreateDialogOpen(false);
      resetForm();
      loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to create user");
    }
  };

  const handleInactivateUser = async () => {
    if (!selectedUser) return;

    try {
      setError(null);
      await updateUser(selectedUser.id, { status: "inactive" });
      setSuccess("User inactivated successfully");
      setInactivateDialogOpen(false);
      setSelectedUser(null);
      loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to inactivate user");
    }
  };

  const handleDeleteUser = async () => {
    if (!selectedUser) return;

    try {
      setError(null);
      await deleteUser(selectedUser.id);
      setSuccess("User permanently deleted");
      setDeleteDialogOpen(false);
      setSelectedUser(null);
      loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to delete user");
    }
  };

  const openCreateDialog = () => {
    resetForm();
    setCreateSubmitAttempted(false);
    setShowCreatePassword(false);
    setCreateDialogOpen(true);
  };

  const openInactivateDialog = (user: User) => {
    setSelectedUser(user);
    setInactivateDialogOpen(true);
  };

  const handleToggleUserStatus = async (user: User) => {
    try {
      setError(null);
      const newStatus = user.status === "active" ? "inactive" : "active";
      await updateUser(user.id, { status: newStatus });
      setSuccess(
        `User ${
          newStatus === "active" ? "activated" : "inactivated"
        } successfully`
      );
      loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to update user status");
    }
  };

  const openDeleteDialog = (user: User) => {
    setSelectedUser(user);
    setDeleteDialogOpen(true);
  };

  const resetForm = () => {
    setFormData({
      username: "",
      email: "",
      passwordHash: "",
      role: "developer",
      authMethod: "local",
    });
    setSelectedUser(null);
    setCreateSubmitAttempted(false);
  };

  const formatDate = (dateString?: string) => {
    if (!dateString) return "Never";
    return new Date(dateString).toLocaleString();
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
        <Typography variant="h6">User Management</Typography>
        <Box sx={{ display: "flex", gap: 2 }}>
          <Button
            variant="outlined"
            startIcon={<RefreshIcon />}
            onClick={loadUsers}
          >
            Refresh
          </Button>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={openCreateDialog}
          >
            Add User
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

      {/* Users Table */}
      <Card sx={{ width: "100%", minHeight: 200 }}>
        <CardContent>
          {loading ? (
            <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
              <CircularProgress />
            </Box>
          ) : users.length === 0 ? (
            <Box sx={{ textAlign: "center", p: 4 }}>
              <Typography variant="body1" color="text.secondary">
                No users found. Create your first user to get started.
              </Typography>
            </Box>
          ) : (
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>User</TableCell>
                    <TableCell>Email</TableCell>
                    <TableCell>Role</TableCell>
                    <TableCell>Auth Method</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Last Login</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {users.map((user) => (
                    <TableRow key={user.id} hover>
                      <TableCell>
                        <Box
                          sx={{ display: "flex", alignItems: "center", gap: 2 }}
                        >
                          <Avatar
                            src={user.profilePicture}
                            alt={user.username}
                            sx={{ width: 32, height: 32 }}
                          >
                            {getUserInitials(user.username)}
                          </Avatar>
                          <Box>
                            <Typography variant="body2" fontWeight="medium">
                              {user.username}
                            </Typography>
                            <Typography
                              variant="caption"
                              color="text.secondary"
                            >
                              {user.id}
                            </Typography>
                          </Box>
                        </Box>
                      </TableCell>
                      <TableCell>{user.email}</TableCell>
                      <TableCell>{formatRole(user.role)}</TableCell>
                      <TableCell>{formatAuthMethod(user.authMethod)}</TableCell>
                      <TableCell>
                        <Chip
                          label={formatStatus(user.status)}
                          color={getStatusColor(user.status)}
                          size="small"
                        />
                      </TableCell>
                      <TableCell>{formatDate(user.lastLogin)}</TableCell>
                      <TableCell align="right">
                        <IconButton
                          size="small"
                          onClick={() => handleToggleUserStatus(user)}
                          title={
                            user.status === "active"
                              ? "Inactivate user"
                              : "Activate user"
                          }
                          color={
                            user.status === "active" ? "warning" : "success"
                          }
                        >
                          {user.status === "active" ? (
                            <BlockIcon fontSize="small" />
                          ) : (
                            <CheckCircleIcon fontSize="small" />
                          )}
                        </IconButton>
                        <IconButton
                          size="small"
                          onClick={() => openDeleteDialog(user)}
                          title="Permanently delete user"
                        >
                          <DeleteIcon fontSize="small" />
                        </IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Create User Dialog */}
      <Dialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Create New User</DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 2 }}>
            <TextField
              label="Email"
              type="email"
              value={formData.email || ""}
              onChange={(e) =>
                setFormData({ ...formData, email: e.target.value })
              }
              fullWidth
              required
              helperText="Email will be used as user ID (must be unique)"
            />
            <TextField
              label="Full Name"
              value={formData.username || ""}
              onChange={(e) =>
                setFormData({ ...formData, username: e.target.value })
              }
              fullWidth
              required
            />
            <TextField
              label="Password"
              type={showCreatePassword ? "text" : "password"}
              value={formData.passwordHash || ""}
              onChange={(e) =>
                setFormData({ ...formData, passwordHash: e.target.value })
              }
              fullWidth
              required
              error={createSubmitAttempted && !formData.passwordHash}
              helperText="All users use local authentication"
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton
                      aria-label="toggle password visibility"
                      onClick={() => setShowCreatePassword((prev) => !prev)}
                      edge="end"
                    >
                      {showCreatePassword ? (
                        <VisibilityOffIcon />
                      ) : (
                        <VisibilityIcon />
                      )}
                    </IconButton>
                  </InputAdornment>
                ),
              }}
            />
            <TextField
              select
              label="Role"
              value={formData.role || "developer"}
              onChange={(e) => {
                const newRole = e.target.value as "admin" | "developer";
                setFormData({
                  ...formData,
                  role: newRole,
                });
              }}
              fullWidth
              helperText="Scopes are automatically assigned based on role"
            >
              <MenuItem value="developer">
                Developer - Tokens & Client Registration access (user/*.*)
              </MenuItem>
              <MenuItem value="admin">
                Administrator - Full UI access (user/*.*, system/*.*)
              </MenuItem>
            </TextField>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleCreateUser} variant="contained">
            Create User
          </Button>
        </DialogActions>
      </Dialog>

      {/* Inactivate User Dialog */}
      <Dialog
        open={inactivateDialogOpen}
        onClose={() => setInactivateDialogOpen(false)}
      >
        <DialogTitle>Inactivate User</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to inactivate user{" "}
            <strong>{selectedUser?.username}</strong>? The user will not be able
            to log in but their data will be preserved. You can reactivate them
            later.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setInactivateDialogOpen(false)}>Cancel</Button>
          <Button
            onClick={handleInactivateUser}
            variant="contained"
            color="warning"
          >
            Inactivate
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete User Dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
      >
        <DialogTitle>Permanently Delete User</DialogTitle>
        <DialogContent>
          <Typography color="error" sx={{ mb: 1 }}>
            ⚠️ <strong>WARNING: This action cannot be undone!</strong>
          </Typography>
          <Typography>
            Are you sure you want to permanently delete user{" "}
            <strong>{selectedUser?.username}</strong>? This will remove all
            their data from the system.
          </Typography>
          <Typography sx={{ mt: 2 }} color="text.secondary">
            Consider using "Inactivate" instead to preserve their data.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDeleteUser} variant="contained">
            Permanently Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Users;
