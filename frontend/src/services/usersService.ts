import axios from "../config/axiosConfig";

export interface User {
  id: string;
  username: string;
  email: string;
  passwordHash?: string;
  role: "admin" | "api_user";
  authMethod: "local" | "social";
  status: "active" | "inactive" | "suspended";
  createdBy: string;
  createdAt: string;
  lastLogin?: string;
  profilePicture?: string;
  socialAuthId?: string;
}

export interface CreateUserRequest {
  id: string;
  username: string;
  email: string;
  passwordHash?: string;
  role: "admin" | "api_user";
  authMethod: "local" | "social";
}

export interface UpdateUserRequest {
  username?: string;
  role?: "admin" | "api_user";
  status?: "active" | "inactive" | "suspended";
  passwordHash?: string;
}

/**
 * Get all users
 */
export const getAllUsers = async (): Promise<User[]> => {
  const response = await axios.get("/api/admin/users");
  return response.data;
};

/**
 * Get user by ID
 */
export const getUserById = async (userId: string): Promise<User> => {
  const response = await axios.get(`/api/admin/users/${userId}`);
  return response.data;
};

/**
 * Get current user profile
 */
export const getCurrentUser = async (): Promise<User> => {
  const response = await axios.get("/api/admin/users/me");
  return response.data;
};

/**
 * Create new user
 */
export const createUser = async (user: CreateUserRequest): Promise<User> => {
  const response = await axios.post("/api/admin/users", user);
  return response.data;
};

/**
 * Update user
 */
export const updateUser = async (
  userId: string,
  updates: UpdateUserRequest
): Promise<User> => {
  const response = await axios.put(`/api/admin/users/${userId}`, updates);
  return response.data;
};

/**
 * Delete user (soft delete)
 */
export const deleteUser = async (userId: string): Promise<void> => {
  await axios.delete(`/api/admin/users/${userId}`);
};

/**
 * Check if any users exist in the system
 */
export const checkUsersExist = async (): Promise<boolean> => {
  const response = await axios.get("/api/admin/users/exists");
  return response.data.hasUsers;
};

/**
 * Get user initials for avatar
 */
export const getUserInitials = (username: string): string => {
  if (!username) return "U";

  const parts = username.split(" ");
  if (parts.length >= 2) {
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }
  return username.substring(0, Math.min(2, username.length)).toUpperCase();
};

/**
 * Format user role for display
 */
export const formatRole = (role: string): string => {
  switch (role) {
    case "admin":
      return "Administrator";
    case "api_user":
      return "API User";
    default:
      return role;
  }
};

/**
 * Format auth method for display
 */
export const formatAuthMethod = (authMethod: string): string => {
  switch (authMethod) {
    case "local":
      return "Email/Password";
    case "social":
      return "Social (Google/GitHub)";
    default:
      return authMethod;
  }
};

/**
 * Format status for display
 */
export const formatStatus = (status: string): string => {
  switch (status) {
    case "active":
      return "Active";
    case "inactive":
      return "Inactive";
    case "suspended":
      return "Suspended";
    default:
      return status;
  }
};

/**
 * Get status color for badge
 */
export const getStatusColor = (
  status: string
): "success" | "default" | "error" => {
  switch (status) {
    case "active":
      return "success";
    case "inactive":
      return "default";
    case "suspended":
      return "error";
    default:
      return "default";
  }
};
