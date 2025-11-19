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
} from "@mui/material";
import {
  Add as AddIcon,
  Edit as EditIcon,
  Delete as DeleteIcon,
  Refresh as RefreshIcon,
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

const Users: React.FC = () => {
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Dialog states
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [editDialogOpen, setEditDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);

  // Form states
  const [formData, setFormData] = useState<Partial<CreateUserRequest>>({
    username: "",
    email: "",
    passwordHash: "",
    role: "api_user",
    authMethod: "local",
  });

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

  const handleUpdateUser = async () => {
    if (!selectedUser) return;

    try {
      setError(null);
      const updates: UpdateUserRequest = {};
      if (formData.username) updates.username = formData.username;
      if (formData.role) updates.role = formData.role;
      if (formData.passwordHash) updates.passwordHash = formData.passwordHash;

      await updateUser(selectedUser.id, updates);
      setSuccess("User updated successfully");
      setEditDialogOpen(false);
      resetForm();
      loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to update user");
    }
  };

  const handleDeleteUser = async () => {
    if (!selectedUser) return;

    try {
      setError(null);
      await deleteUser(selectedUser.id);
      setSuccess("User deleted successfully");
      setDeleteDialogOpen(false);
      setSelectedUser(null);
      loadUsers();
    } catch (err: any) {
      setError(err.response?.data?.error || "Failed to delete user");
    }
  };

  const openCreateDialog = () => {
    resetForm();
    setCreateDialogOpen(true);
  };

  const openEditDialog = (user: User) => {
    setSelectedUser(user);
    setFormData({
      username: user.username,
      role: user.role,
      passwordHash: "",
    });
    setEditDialogOpen(true);
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
      role: "api_user",
      authMethod: "local",
    });
    setSelectedUser(null);
  };

  const formatDate = (dateString?: string) => {
    if (!dateString) return "Never";
    return new Date(dateString).toLocaleString();
  };

  return (
    <Box sx={{ p: 3 }}>
      {/* Header */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 3,
        }}
      >
        <Typography variant="h4" component="h1">
          User Management
        </Typography>
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
      <Card>
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
                          onClick={() => openEditDialog(user)}
                          title="Edit user"
                        >
                          <EditIcon fontSize="small" />
                        </IconButton>
                        <IconButton
                          size="small"
                          onClick={() => openDeleteDialog(user)}
                          title="Delete user"
                          color="error"
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
              type="password"
              value={formData.passwordHash || ""}
              onChange={(e) =>
                setFormData({ ...formData, passwordHash: e.target.value })
              }
              fullWidth
              helperText="Required for local authentication"
            />
            <TextField
              select
              label="Role"
              value={formData.role || "api_user"}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  role: e.target.value as "admin" | "api_user",
                })
              }
              fullWidth
            >
              <MenuItem value="api_user">
                API User (Token generation only)
              </MenuItem>
              <MenuItem value="admin">Administrator (Full access)</MenuItem>
            </TextField>
            <TextField
              select
              label="Authentication Method"
              value={formData.authMethod || "local"}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  authMethod: e.target.value as "local" | "social",
                })
              }
              fullWidth
            >
              <MenuItem value="local">Local (Email/Password)</MenuItem>
              <MenuItem value="social">Social (Google/GitHub)</MenuItem>
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

      {/* Edit User Dialog */}
      <Dialog
        open={editDialogOpen}
        onClose={() => setEditDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Edit User: {selectedUser?.username}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 2 }}>
            <TextField
              label="Full Name"
              value={formData.username || ""}
              onChange={(e) =>
                setFormData({ ...formData, username: e.target.value })
              }
              fullWidth
            />
            <TextField
              select
              label="Role"
              value={formData.role || "api_user"}
              onChange={(e) =>
                setFormData({
                  ...formData,
                  role: e.target.value as "admin" | "api_user",
                })
              }
              fullWidth
            >
              <MenuItem value="api_user">
                API User (Token generation only)
              </MenuItem>
              <MenuItem value="admin">Administrator (Full access)</MenuItem>
            </TextField>
            <TextField
              label="New Password"
              type="password"
              value={formData.passwordHash || ""}
              onChange={(e) =>
                setFormData({ ...formData, passwordHash: e.target.value })
              }
              fullWidth
              helperText="Leave blank to keep current password"
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleUpdateUser} variant="contained">
            Update User
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete User Dialog */}
      <Dialog
        open={deleteDialogOpen}
        onClose={() => setDeleteDialogOpen(false)}
      >
        <DialogTitle>Delete User</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to delete user{" "}
            <strong>{selectedUser?.username}</strong>? This action will
            deactivate the user account.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogOpen(false)}>Cancel</Button>
          <Button onClick={handleDeleteUser} variant="contained" color="error">
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default Users;
