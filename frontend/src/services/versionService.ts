export interface BackendVersionInfo {
  version: string;
  description?: string;
}

/**
 * Fetch backend Couchbase FHIR Server version.
 * Currently only returns { version } from /api/config/version.
 * Fallback: 'unknown' if request fails.
 */
export async function fetchBackendVersion(
  signal?: AbortSignal
): Promise<BackendVersionInfo> {
  try {
    const res = await fetch("/api/config/version", { signal });
    if (!res.ok) {
      return { version: "unknown" };
    }
    const data = await res.json();
    return {
      version: data.version ?? "unknown",
      description: data.description,
    };
  } catch (e) {
    return { version: "unknown" };
  }
}
