import axios from "../config/axiosConfig";

export interface OAuthClient {
  clientId: string;
  clientSecret?: string;
  clientName: string;
  publisherUrl?: string;
  clientType: string;
  authenticationType: string;
  launchType: string;
  redirectUris: string[];
  scopes: string[];
  pkceEnabled: boolean;
  pkceMethod?: string;
  status?: string;
  createdBy?: string;
  createdAt?: string;
  lastUsed?: string;
  bulkGroupId?: string | null;
}

export interface CreateClientRequest {
  clientName: string;
  publisherUrl?: string;
  clientType: string;
  authenticationType: string;
  launchType: string;
  redirectUris: string[];
  scopes: string[];
  pkceEnabled: boolean;
  pkceMethod?: string;
}

export const getAllClients = async (): Promise<OAuthClient[]> => {
  const response = await axios.get("/api/admin/oauth-clients");
  return response.data;
};

export const createClient = async (client: CreateClientRequest): Promise<OAuthClient> => {
  const response = await axios.post("/api/admin/oauth-clients", client);
  return response.data;
};

export const revokeClient = async (clientId: string): Promise<void> => {
  await axios.post(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}/revoke`);
};

export const deleteClient = async (clientId: string): Promise<void> => {
  await axios.delete(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}`);
};

export const attachBulkGroup = async (clientId: string, bulkGroupId: string): Promise<OAuthClient> => {
  const resp = await axios.post(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}/bulk-group`, { bulkGroupId });
  return resp.data;
};

export const getBulkGroup = async (clientId: string): Promise<OAuthClient> => {
  const resp = await axios.get(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}/bulk-group`);
  return resp.data;
};

export const detachBulkGroup = async (clientId: string): Promise<OAuthClient> => {
  const resp = await axios.delete(`/api/admin/oauth-clients/${encodeURIComponent(clientId)}/bulk-group`);
  return resp.data;
};

const defaultExport = {
  getAllClients,
  createClient,
  attachBulkGroup,
  getBulkGroup,
  detachBulkGroup,
};

export default defaultExport;
