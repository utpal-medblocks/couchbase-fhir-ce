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

export interface DocumentMetadata {
  id: string;
  versionId: string;
  lastUpdated: string;
  code: string;
  display: string;
  deleted: boolean;
  isCurrentVersion: boolean;
  resourceType?: string; // Optional - only present for General collection
}

export interface DocumentMetadataResponse {
  bucketName: string;
  collectionName: string;
  documents: DocumentMetadata[];
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
  private baseURL: string;

  constructor() {
    // Use relative URL for containerized deployments (HAProxy routes /api/* to backend)
    // In development, this will resolve to the dev server proxy
    // In production, HAProxy routes /api/* requests to the backend service
    this.baseURL = "/api/fhir-resources";
  }

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
   * Get document metadata using FTS search for a FHIR collection with pagination
   */
  async getDocumentMetadata(
    request: DocumentKeyRequest
  ): Promise<DocumentMetadataResponse> {
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
        `${this.baseURL}/document-metadata?${params}`
      );
      return response.data;
    } catch (error) {
      console.error("Failed to get document metadata:", error);
      throw error;
    }
  }

  /**
   * Get version history for a specific document ID
   */
  async getVersionHistory(
    connectionName: string,
    bucketName: string,
    documentId: string
  ): Promise<DocumentMetadata[]> {
    try {
      const params = new URLSearchParams({
        connectionName,
        bucketName,
        documentId,
      });

      const response = await axios.get(
        `${this.baseURL}/version-history?${params}`
      );
      return response.data;
    } catch (error) {
      console.error("Failed to get version history:", error);
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
