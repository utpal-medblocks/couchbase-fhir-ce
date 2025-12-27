import axios from "../config/axiosConfig";

const API_BASE = "/api/admin/groups";

export interface GroupRequest {
  name: string;
  resourceType: string;
  filter: string;
  createdBy?: string;
}

export interface GroupResponse {
  id: string;
  name: string;
  filter: string;
  resourceType: string;
  memberCount: number;
  createdBy: string;
  lastUpdated: string;
  lastRefreshed?: string;
  members?: string[]; // Only included in getById
}

export interface PreviewRequest {
  resourceType: string;
  filter: string;
}

export interface PreviewResponse {
  totalCount: number;
  sampleResources: Array<{
    id: string;
    resourceType: string;
    name?: string;
    birthDate?: string;
    gender?: string;
    [key: string]: any;
  }>;
  resourceType: string;
  filter: string;
}

export const groupService = {
  /**
   * Preview a filter before creating a group
   */
  async preview(request: PreviewRequest): Promise<PreviewResponse> {
    const resp = await axios.post<PreviewResponse>(
      `${API_BASE}/preview`,
      request
    );
    return resp.data;
  },

  /**
   * Create a new FHIR Group from a filter
   */
  async create(request: GroupRequest): Promise<GroupResponse> {
    const resp = await axios.post<GroupResponse>(API_BASE, request);
    return resp.data;
  },

  /**
   * Update an existing FHIR Group (re-run filter with same ID)
   */
  async update(id: string, request: GroupRequest): Promise<GroupResponse> {
    const resp = await axios.put<GroupResponse>(`${API_BASE}/${id}`, request);
    return resp.data;
  },

  /**
   * Get all groups
   */
  async getAll(): Promise<GroupResponse[]> {
    const resp = await axios.get<{ groups: GroupResponse[] }>(API_BASE);
    return resp.data.groups;
  },

  /**
   * Get a specific group by ID
   */
  async getById(id: string): Promise<GroupResponse> {
    const resp = await axios.get<GroupResponse>(`${API_BASE}/${id}`);
    return resp.data;
  },

  /**
   * Refresh a group (re-run its filter)
   */
  async refresh(id: string): Promise<{
    id: string;
    name: string;
    memberCount: number;
    lastRefreshed: string;
    message: string;
  }> {
    const resp = await axios.post(`${API_BASE}/${id}/refresh`);
    return resp.data;
  },

  /**
   * Remove a specific member from a group
   */
  async removeMember(
    groupId: string,
    memberReference: string
  ): Promise<{ message: string; groupId: string; newMemberCount: number }> {
    // URL encode the member reference (e.g., "Patient/123" -> "Patient%2F123")
    const encoded = encodeURIComponent(memberReference);
    const resp = await axios.delete(
      `${API_BASE}/${groupId}/members/${encoded}`
    );
    return resp.data;
  },

  /**
   * Delete a group
   */
  async remove(id: string): Promise<void> {
    await axios.delete(`${API_BASE}/${id}`);
  },
};

export default groupService;
