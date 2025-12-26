import axios from "../config/axiosConfig";

const API_BASE = "/api/admin/bulk-groups";

export interface BulkGroupRequest {
  id?: string;
  name?: string;
  description?: string;
  patientIds?: string[];
}

export interface BulkGroupResponse {
  id: string;
  name?: string;
  description?: string;
  patientIds?: string[];
  patientNames?: Record<string, string>;
  createdAt?: string;
}

export const bulkGroupService = {
  async getAll(): Promise<BulkGroupResponse[]> {
    const resp = await axios.get<BulkGroupResponse[]>(API_BASE);
    return resp.data;
  },

  async getById(id: string): Promise<BulkGroupResponse> {
    const resp = await axios.get<BulkGroupResponse>(`${API_BASE}/${encodeURIComponent(id)}`);
    return resp.data;
  },

  async create(g: BulkGroupRequest): Promise<BulkGroupResponse> {
    const resp = await axios.post<BulkGroupResponse>(API_BASE, g);
    return resp.data;
  },

  async update(id: string, g: Partial<BulkGroupRequest>): Promise<BulkGroupResponse> {
    const resp = await axios.put<BulkGroupResponse>(`${API_BASE}/${encodeURIComponent(id)}`, g);
    return resp.data;
  },

  async remove(id: string): Promise<void> {
    await axios.delete(`${API_BASE}/${encodeURIComponent(id)}`);
  },
};

export default bulkGroupService;
