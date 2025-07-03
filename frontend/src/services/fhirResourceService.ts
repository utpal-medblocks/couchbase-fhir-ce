import axios from "axios";

export interface DocumentKeyRequest {
  connectionName: string;
  bucketName: string;
  collectionName: string;
  page?: number;
  pageSize?: number;
  patientId?: string;
}

export interface DocumentKeyResponse {
  bucketName: string;
  collectionName: string;
  documentKeys: string[];
  totalCount: number;
  page: number;
  pageSize: number;
  hasMore: boolean;
}

export interface DocumentRequest {
  connectionName: string;
  bucketName: string;
  collectionName: string;
  documentKey: string;
}

export class FhirResourceService {
  private static instance: FhirResourceService;
  private baseURL = "http://localhost:8080/api/fhir-resources";

  static getInstance(): FhirResourceService {
    if (!FhirResourceService.instance) {
      FhirResourceService.instance = new FhirResourceService();
    }
    return FhirResourceService.instance;
  }

  /**
   * Get document keys for a FHIR collection with pagination
   */
  async getDocumentKeys(
    request: DocumentKeyRequest
  ): Promise<DocumentKeyResponse> {
    try {
      const params = new URLSearchParams({
        connectionName: request.connectionName,
        bucketName: request.bucketName,
        collectionName: request.collectionName,
        page: (request.page || 0).toString(),
        pageSize: (request.pageSize || 10).toString(),
      });

      if (request.patientId) {
        params.append("patientId", request.patientId);
      }

      const response = await axios.get(
        `${this.baseURL}/document-keys?${params}`
      );
      return response.data;
    } catch (error) {
      console.error("Failed to get document keys:", error);
      throw error;
    }
  }

  /**
   * Get a specific FHIR document by its key
   */
  async getDocument(request: DocumentRequest): Promise<any> {
    try {
      const params = new URLSearchParams({
        connectionName: request.connectionName,
        bucketName: request.bucketName,
        collectionName: request.collectionName,
        documentKey: request.documentKey,
      });

      const response = await axios.get(`${this.baseURL}/document?${params}`);
      return response.data;
    } catch (error) {
      console.error("Failed to get document:", error);
      throw error;
    }
  }
}

export default FhirResourceService.getInstance();
