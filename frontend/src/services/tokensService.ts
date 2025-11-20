import axios from "../config/axiosConfig";

export interface Token {
  id: string;
  userId: string;
  appName: string;
  tokenHash: string;
  status: "active" | "revoked" | "expired";
  createdAt: string;
  expiresAt: string;
  lastUsedAt?: string;
  createdBy: string;
  scopes: string[];
}

export interface GenerateTokenRequest {
  appName: string;
  scopes: string[];
}

export interface GenerateTokenResponse {
  token: string; // The actual JWT token (show once!)
  tokenMetadata: Token;
}

/**
 * Generate a new API token
 */
export const generateToken = async (
  request: GenerateTokenRequest
): Promise<GenerateTokenResponse> => {
  const response = await axios.post<GenerateTokenResponse>(
    "/api/admin/tokens",
    request
  );
  return response.data;
};

/**
 * Get all tokens for the current user
 */
export const getMyTokens = async (): Promise<Token[]> => {
  const response = await axios.get<Token[]>("/api/admin/tokens");
  return response.data;
};

/**
 * Get all tokens (admin only)
 */
export const getAllTokens = async (): Promise<Token[]> => {
  const response = await axios.get<Token[]>("/api/admin/tokens/all");
  return response.data;
};

/**
 * Get token by ID
 */
export const getTokenById = async (id: string): Promise<Token> => {
  const response = await axios.get<Token>(`/api/admin/tokens/${id}`);
  return response.data;
};

/**
 * Revoke a token
 */
export const revokeToken = async (id: string): Promise<void> => {
  await axios.delete(`/api/admin/tokens/${id}`);
};
