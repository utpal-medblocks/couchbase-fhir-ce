import axios from "axios";

const API_BASE_URL = "/api/auth";

/**
 * Login request interface
 */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * User info interface
 */
export interface UserInfo {
  email: string;
  name: string;
  role: string;
  allowedScopes: string[];
}

/**
 * Login response interface
 */
export interface LoginResponse {
  token: string;
  user: UserInfo;
}

/**
 * Auth service for Admin UI authentication
 */
export const authService = {
  /**
   * Login with email and password
   * @param credentials Email and password
   * @returns Promise with token and user info
   */
  async login(credentials: LoginRequest): Promise<LoginResponse> {
    try {
      console.log("📡 POST /api/auth/login", { email: credentials.email });
      const response = await axios.post<LoginResponse>(
        `${API_BASE_URL}/login`,
        credentials
      );
      console.log("📡 Login response received", response.data);
      return response.data;
    } catch (error: any) {
      console.error("📡 Login request failed:", error);
      if (error.response?.data?.error) {
        throw new Error(error.response.data.error);
      }
      throw new Error("Login failed. Please try again.");
    }
  },

  /**
   * Validate token (optional - for checking if token is still valid)
   * @param token JWT token
   * @returns Promise with user info if valid
   */
  async validateToken(token: string): Promise<UserInfo> {
    try {
      const response = await axios.get<UserInfo>(`${API_BASE_URL}/validate`, {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });
      return response.data;
    } catch (error) {
      throw new Error("Token validation failed");
    }
  },
};

