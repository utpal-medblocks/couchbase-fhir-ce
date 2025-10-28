export interface BackendVersionInfo {
  version: string;
  description?: string;
  buildTime?: string;
  name?: string;
  group?: string;
  artifact?: string;
}

/**
 * Fetch backend Couchbase FHIR Server version.
 * Returns { version, buildTime } from /api/config/version.
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
      buildTime: data.buildTime,
      name: data.name,
      group: data.group,
      artifact: data.artifact,
    };
  } catch (e) {
    return { version: "unknown" };
  }
}
