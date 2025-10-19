import axios from "axios";

export interface ConfigSummary {
  success?: boolean;
  configPath?: string;
  configExists?: boolean;
  message?: string;
  error?: string;
  connection?: {
    server?: string;
    username?: string;
    passwordMasked?: string;
    serverType?: string;
    sslEnabled?: boolean;
  };
  app?: {
    autoConnect?: boolean;
    showConnectionDialog?: boolean;
  };
}

export async function fetchConfigSummary(): Promise<ConfigSummary> {
  const res = await axios.get("/api/config/summary");
  return res.data as ConfigSummary;
}

export async function retryAutoConnect(): Promise<{
  success: boolean;
  message?: string;
  error?: string;
}> {
  const res = await axios.post("/api/config/retry-auto-connect");
  return res.data as { success: boolean; message?: string; error?: string };
}
