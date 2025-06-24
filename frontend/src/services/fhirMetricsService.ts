export interface FhirMetrics {
  // FHIR Server Health
  server: {
    status: "UP" | "DOWN" | "DEGRADED";
    uptime: string;
    cpuUsage: number; // percentage
    memoryUsage: number; // percentage
    diskUsage: number; // percentage
    jvmThreads: number;
  };

  // FHIR Version Info
  version: {
    fhirVersion: string;
    serverVersion: string;
    buildNumber: string;
  };

  // FHIR Resources Operations (simplified to 3 main operations)
  operations: {
    read: { count: number; avgLatency: number; successRate: number };
    create: { count: number; avgLatency: number; successRate: number };
    search: { count: number; avgLatency: number; successRate: number };
  };

  // Overall Performance
  overall: {
    totalOperations: number;
    currentOpsPerSec: number;
    avgOpsPerSec: number;
  };
}

export interface DashboardMetricsResponse {
  health: {
    status: string;
    details?: any;
  };
  systemMetrics: {
    [key: string]: number;
  };
  jvmMetrics: {
    [key: string]: number;
  };
  fhirMetrics: {
    server: {
      status: string;
      uptime: string;
      cpuUsage: number;
      memoryUsage: number;
      diskUsage: number;
      jvmThreads: number;
    };
    version: {
      fhirVersion: string;
      serverVersion: string;
      buildNumber: string;
    };
    operations: {
      read: { count: number; avgLatency: number; successRate: number };
      create: { count: number; avgLatency: number; successRate: number };
      search: { count: number; avgLatency: number; successRate: number };
    };
    overall: {
      totalOperations: number;
      currentOpsPerSec: number;
      avgOpsPerSec: number;
    };
  };
  uptime: string;
  timestamp: number;
}

class FhirMetricsService {
  private baseUrl = "/api/dashboard";

  async getDashboardMetrics(): Promise<DashboardMetricsResponse> {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);

    try {
      const response = await fetch(`${this.baseUrl}/metrics`, {
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();

      if (data.error) {
        throw new Error(data.error);
      }

      return data;
    } catch (error) {
      clearTimeout(timeoutId);
      throw error;
    }
  }

  async getFhirMetrics(): Promise<FhirMetrics> {
    const data = await this.getDashboardMetrics();

    // Transform the data to match our interface
    return {
      server: {
        status: (data.fhirMetrics?.server?.status ||
          data.health?.status ||
          "DOWN") as "UP" | "DOWN" | "DEGRADED",
        uptime: data.fhirMetrics?.server?.uptime || data.uptime || "0s",
        cpuUsage: data.systemMetrics?.["cpu.usage.percent"] || 0,
        memoryUsage: data.jvmMetrics?.["memory.usage.percent"] || 0,
        diskUsage: data.systemMetrics?.["disk.usage.percent"] || 0,
        jvmThreads: data.jvmMetrics?.["threads.live"] || 0,
      },
      version: {
        fhirVersion: data.fhirMetrics?.version?.fhirVersion || "R4",
        serverVersion: data.fhirMetrics?.version?.serverVersion || "Unknown",
        buildNumber: data.fhirMetrics?.version?.buildNumber || "Unknown",
      },
      operations: {
        read: {
          count: data.fhirMetrics?.operations?.read?.count || 0,
          avgLatency: data.fhirMetrics?.operations?.read?.avgLatency || 0,
          successRate: data.fhirMetrics?.operations?.read?.successRate || 0,
        },
        create: {
          count: data.fhirMetrics?.operations?.create?.count || 0,
          avgLatency: data.fhirMetrics?.operations?.create?.avgLatency || 0,
          successRate: data.fhirMetrics?.operations?.create?.successRate || 0,
        },
        search: {
          count: data.fhirMetrics?.operations?.search?.count || 0,
          avgLatency: data.fhirMetrics?.operations?.search?.avgLatency || 0,
          successRate: data.fhirMetrics?.operations?.search?.successRate || 0,
        },
      },
      overall: {
        totalOperations: data.fhirMetrics?.overall?.totalOperations || 0,
        currentOpsPerSec: data.fhirMetrics?.overall?.currentOpsPerSec || 0,
        avgOpsPerSec: data.fhirMetrics?.overall?.avgOpsPerSec || 0,
      },
    };
  }

  async getHealthStatus(): Promise<{ status: string; details?: any }> {
    const response = await fetch(`${this.baseUrl}/health`);

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return response.json();
  }

  async getSystemMetrics(): Promise<{
    systemMetrics: { [key: string]: number };
    jvmMetrics: { [key: string]: number };
  }> {
    const response = await fetch(`${this.baseUrl}/system`);

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    return response.json();
  }

  async refreshMetrics(): Promise<void> {
    const response = await fetch(`${this.baseUrl}/refresh`);

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
  }
}

export const fhirMetricsService = new FhirMetricsService();
