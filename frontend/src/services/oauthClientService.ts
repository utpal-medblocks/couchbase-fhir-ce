import axios from "../config/axiosConfig";

export interface OAuthClient {
  clientId: string;
  clientSecret?: string; // Only provided once upon creation
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

export interface CreateClientRequest {
  clientName: string;
  publisherUrl?: string;
  clientType: "patient" | "provider" | "system";
  authenticationType: "public" | "confidential";
  launchType: "standalone" | "ehr-launch";
  redirectUris: string[];
  scopes: string[];
  pkceEnabled: boolean;
  pkceMethod: "S256" | "plain";
}

/**
 * Get all OAuth clients
 */
export const getAllClients = async (): Promise<OAuthClient[]> => {
  const response = await axios.get("/api/admin/oauth-clients");
  return response.data;
};

/**
 * Get OAuth client by ID
 */
export const getClientById = async (clientId: string): Promise<OAuthClient> => {
  const response = await axios.get(`/api/admin/oauth-clients/${clientId}`);
  return response.data;
};

/**
 * Create new OAuth client
 */
export const createClient = async (
  client: CreateClientRequest
): Promise<OAuthClient> => {
  const response = await axios.post("/api/admin/oauth-clients", client);
  return response.data;
};

/**
 * Update OAuth client
 */
export const updateClient = async (
  clientId: string,
  updates: Partial<OAuthClient>
): Promise<OAuthClient> => {
  const response = await axios.put(
    `/api/admin/oauth-clients/${clientId}`,
    updates
  );
  return response.data;
};

/**
 * Revoke OAuth client
 */
export const revokeClient = async (clientId: string): Promise<void> => {
  await axios.post(`/api/admin/oauth-clients/${clientId}/revoke`);
};

/**
 * Delete OAuth client
 */
export const deleteClient = async (clientId: string): Promise<void> => {
  await axios.delete(`/api/admin/oauth-clients/${clientId}`);
};
