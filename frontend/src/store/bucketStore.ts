import { create } from "zustand";

export type UUID = string;

export interface SchemaDetails {
  schemaId: string;
  schema: any;
  description: string;
}

export interface CollectionDetails {
  collectionName: string;
  scopeName: string;
  bucketName: string;
  items: number; // kv_collection_item_count
  diskSize: number; // kv_collection_data_size_bytes
  memUsed: number; // kv_collection_mem_used_bytes
  ops: number; // kv_collection_ops
  indexes: number; // index_count
  maxTTL?: number; // TTL
  schemas?: SchemaDetails[]; //support for multiple schemas per collection
  schemaDescription?: string;
  sampleDoc?: any;
}

export interface FhirValidationConfig {
  mode: "strict" | "lenient" | "disabled";
  profile: "none" | "us-core";
}

export interface FhirLogsConfig {
  enableSystem: boolean;
  enableCRUDAudit: boolean;
  enableSearchAudit: boolean;
  rotationBy: "size" | "days";
  number: number;
  s3Endpoint: string;
}

export interface FhirConfiguration {
  fhirRelease: string;
  validation: FhirValidationConfig;
  logs: FhirLogsConfig;
  version?: string;
  description?: string;
  createdAt?: string;
}

export interface BucketDetails {
  bucketName: string;
  bucketType: string;
  storageBackend: string;
  evictionPolicy: string;
  itemCount: number;
  opsPerSec: number;
  replicaNumber: number;
  ram: number;
  diskUsed: number;
  durabilityMinLevel: string;
  conflictResolutionType: string;
  maxTTL: number;
  quotaPercentUsed: number;
  residentRatio: number;
  cacheHit: number;
  // Collection metrics from backend
  collectionMetrics?: { [key: string]: { [key: string]: any } };
  // FHIR configuration from Admin.config document
  fhirConfig?: FhirConfiguration;
}

export interface IndexDetails {
  bucket: string;
  scope: string;
  collection: string;
  instId: number;
  indexName: string;
  index: string;
  definition: string;
  status: string;
  hosts: string[];
  numPartition: number;
  numReplica: number;
  replicaId: number;
  lastScanTime: string;
  progress: number;
  predicate: string;
  filterStr: string;
}

export interface IndexPerformance {
  reqRate: number;
  resident: number;
  items: number;
  dataSize: number;
  diskSize: number;
  scanLatency: number;
  cacheMissRatio: number;
  mutationsRemaining: number;
  fragmentation: number;
}

/**
 * Initialization status for single-tenant FHIR system
 * Mirrors backend InitializationStatus
 */
export interface InitializationStatus {
  status:
    | "NOT_CONNECTED"
    | "BUCKET_MISSING"
    | "BUCKET_NOT_INITIALIZED"
    | "READY";
  message: string;
  bucketName: string;
  hasConnection: boolean;
  bucketExists: boolean;
  isFhirInitialized: boolean;
}

/**
 * Single-tenant bucket store
 * Manages exactly one FHIR bucket named "fhir" with "default" connection
 */
export type BucketStore = {
  // Single-tenant state
  bucket: BucketDetails | null;
  collections: CollectionDetails[];
  indexDetails: IndexDetails[];
  initializationStatus: InitializationStatus | null;
  status: string;

  // Actions
  setBucket: (bucket: BucketDetails | null) => void;
  setCollections: (collections: CollectionDetails[]) => void;
  setIndexDetails: (indexes: IndexDetails[]) => void;
  setInitializationStatus: (status: InitializationStatus | null) => void;
  setStatus: (status: string) => void;

  // Fetch operations
  fetchInitializationStatus: () => Promise<InitializationStatus>;
  fetchBucketData: () => Promise<BucketDetails | null>;
  fetchFhirConfig: () => Promise<FhirConfiguration | null>;

  // FHIR Configuration methods
  getFhirConfig: () => FhirConfiguration | null;
  setFhirConfig: (config: FhirConfiguration) => void;

  // Initialization
  initializeFhirBucket: () => Promise<{
    operationId: string;
    message: string;
  }>;

  // Clear data
  clearBucketData: () => void;

  // Session storage helpers
  _saveState: () => void;
  _loadState: () => any;
};

export const useBucketStore = create<BucketStore>()((set, get) => ({
  // Initial state
  bucket: null,
  collections: [],
  indexDetails: [],
  initializationStatus: null,
  status: "",

  // Helper to save state to session storage
  _saveState: () => {
    const state = get();
    const stateData = {
      bucket: state.bucket,
      initializationStatus: state.initializationStatus,
    };
    sessionStorage.setItem("bucketStore", JSON.stringify(stateData));
  },

  // Helper to load state from session storage
  _loadState: () => {
    try {
      const stored = sessionStorage.getItem("bucketStore");
      return stored ? JSON.parse(stored) : null;
    } catch {
      return null;
    }
  },

  // Set bucket
  setBucket: (bucket) => {
    set({ bucket });
    setTimeout(() => get()._saveState(), 0);
  },

  // Set collections
  setCollections: (collections) => {
    set({ collections: [...collections] });
  },

  // Set index details
  setIndexDetails: (indexes) => {
    set({ indexDetails: [...indexes] });
  },

  // Set initialization status
  setInitializationStatus: (initializationStatus) => {
    set({ initializationStatus });
    setTimeout(() => get()._saveState(), 0);
  },

  // Set status
  setStatus: (status) => {
    set({ status });
  },

  // Get FHIR configuration
  getFhirConfig: () => {
    const state = get();
    return state.bucket?.fhirConfig || null;
  },

  // Set FHIR configuration
  setFhirConfig: (config) => {
    const state = get();
    if (state.bucket) {
      set({
        bucket: {
          ...state.bucket,
          fhirConfig: config,
        },
      });
    }
  },

  // Fetch initialization status from backend
  fetchInitializationStatus: async () => {
    try {
      const response = await fetch(
        "/api/admin/initialization/status?connectionName=default",
        {
          method: "GET",
          headers: {
            "Content-Type": "application/json",
          },
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const status: InitializationStatus = await response.json();
      get().setInitializationStatus(status);

      return status;
    } catch (error) {
      console.error("Failed to fetch initialization status:", error);

      // Return error status
      const errorStatus: InitializationStatus = {
        status: "NOT_CONNECTED",
        message: "Failed to fetch initialization status",
        bucketName: "fhir",
        hasConnection: false,
        bucketExists: false,
        isFhirInitialized: false,
      };

      get().setInitializationStatus(errorStatus);
      return errorStatus;
    }
  },

  // Fetch FHIR bucket data from backend
  fetchBucketData: async () => {
    try {
      // In single-tenant mode, backend returns only the "fhir" bucket
      const response = await fetch(
        "/api/buckets/fhir/details?connectionName=default",
        {
          method: "GET",
          headers: {
            "Content-Type": "application/json",
          },
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const bucketData: BucketDetails[] = await response.json();

      // Should get exactly one bucket named "fhir"
      const fhirBucket = bucketData.length > 0 ? bucketData[0] : null;

      get().setBucket(fhirBucket);

      if (fhirBucket) {
        // Extract collections from bucket data
        const allCollections: CollectionDetails[] = [];

        if (fhirBucket.collectionMetrics) {
          Object.entries(fhirBucket.collectionMetrics).forEach(
            ([scopeName, scopeData]) => {
              if (
                scopeData &&
                typeof scopeData === "object" &&
                "collections" in scopeData
              ) {
                const collections = scopeData.collections as {
                  [key: string]: any;
                };
                Object.entries(collections).forEach(
                  ([collectionName, metrics]) => {
                    if (metrics && typeof metrics === "object") {
                      // Skip internal collections: Versions and Tombstones
                      if (
                        collectionName !== "Versions" &&
                        collectionName !== "Tombstones"
                      ) {
                        const collectionDetail: CollectionDetails = {
                          collectionName,
                          scopeName,
                          bucketName: fhirBucket.bucketName,
                          items: Number(metrics["items"]) || 0,
                          diskSize: Number(metrics["diskSize"]) || 0,
                          memUsed: Number(metrics["memUsed"]) || 0,
                          ops: Number(metrics["ops"]) || 0,
                          indexes: Number(metrics["indexes"]) || 0,
                          maxTTL: Number(metrics["maxTTL"]) || 0,
                        };
                        allCollections.push(collectionDetail);
                      }
                    }
                  }
                );
              }
            }
          );
        }

        get().setCollections(allCollections);

        // Fetch FHIR config
        await get().fetchFhirConfig();
      } else {
        get().setCollections([]);
      }

      return fhirBucket;
    } catch (error) {
      console.error("Failed to fetch FHIR bucket data:", error);
      get().setBucket(null);
      get().setCollections([]);
      return null;
    }
  },

  // Fetch FHIR configuration from backend
  fetchFhirConfig: async () => {
    try {
      const response = await fetch(
        "/api/admin/fhir-bucket/fhir/config?connectionName=default",
        {
          method: "GET",
          headers: {
            "Content-Type": "application/json",
          },
        }
      );

      if (!response.ok) {
        if (response.status === 404) {
          return null;
        }
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const backendConfig: {
        fhirRelease?: string;
        validationMode?: string;
        validationProfile?: string;
        version?: string;
        createdAt?: string;
        logs?: {
          enableSystem: boolean;
          enableCRUDAudit: boolean;
          enableSearchAudit: boolean;
          rotationBy: string;
          number: number;
          s3Endpoint: string;
        };
      } = await response.json();

      const config: FhirConfiguration = {
        fhirRelease: backendConfig.fhirRelease || "Release 4",
        validation: {
          mode:
            (backendConfig.validationMode as
              | "strict"
              | "lenient"
              | "disabled") || "lenient",
          profile:
            (backendConfig.validationProfile as "none" | "us-core") || "none",
        },
        logs: backendConfig.logs
          ? {
              enableSystem: backendConfig.logs.enableSystem || false,
              enableCRUDAudit: backendConfig.logs.enableCRUDAudit || false,
              enableSearchAudit: backendConfig.logs.enableSearchAudit || false,
              rotationBy:
                (backendConfig.logs.rotationBy as "size" | "days") || "size",
              number: backendConfig.logs.number || 30,
              s3Endpoint: backendConfig.logs.s3Endpoint || "",
            }
          : {
              enableSystem: false,
              enableCRUDAudit: false,
              enableSearchAudit: false,
              rotationBy: "size" as "size" | "days",
              number: 30,
              s3Endpoint: "",
            },
        version: backendConfig.version,
        createdAt: backendConfig.createdAt,
      };

      get().setFhirConfig(config);

      return config;
    } catch (error) {
      console.error("Failed to fetch FHIR config:", error);
      return null;
    }
  },

  // Initialize FHIR bucket (creates scopes, collections, indexes)
  initializeFhirBucket: async () => {
    try {
      const response = await fetch(
        "/api/admin/initialization/initialize?connectionName=default",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
        }
      );

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(
          errorData.message || `HTTP error! status: ${response.status}`
        );
      }

      const result: {
        operationId: string;
        bucketName: string;
        status: string;
        message: string;
      } = await response.json();

      return {
        operationId: result.operationId,
        message: result.message,
      };
    } catch (error) {
      console.error("Failed to initialize FHIR bucket:", error);
      throw error;
    }
  },

  // Clear bucket data
  clearBucketData: () => {
    set({
      bucket: null,
      collections: [],
      indexDetails: [],
      initializationStatus: null,
      status: "",
    });
    sessionStorage.removeItem("bucketStore");
  },
}));

export default useBucketStore;
