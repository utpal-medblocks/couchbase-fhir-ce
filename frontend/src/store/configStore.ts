import { create } from "zustand";
import axios from "axios";
import type { YamlConnectionConfig, AppConfig } from "../types/config";

interface ConfigState extends AppConfig {
  // Actions
  loadYamlConfig: (yamlPath?: string) => Promise<void>;
  setConfigError: (error: string) => void;
  clearConfigError: () => void;
  resetConfig: () => void;
}

const initialState: AppConfig = {
  yamlConfig: undefined,
  configLoaded: false,
  configError: undefined,
};

export const useConfigStore = create<ConfigState>((set, get) => ({
  ...initialState,

  loadYamlConfig: async (yamlPath?: string) => {
    set({ configError: undefined });

    try {
      // Try to load YAML config from backend
      const response = await axios.get("/api/config/yaml", {
        params: { path: yamlPath },
        timeout: 5000,
      });

      if (response.data && response.data.success) {
        const yamlConfig: YamlConnectionConfig = response.data.config;

        set({
          yamlConfig,
          configLoaded: true,
          configError: undefined,
        });

        console.log("✅ YAML config loaded successfully:", yamlConfig);
      } else {
        set({
          configLoaded: true,
          configError: response.data?.error || "Failed to load YAML config",
        });
      }
    } catch (error: any) {
      console.warn("⚠️ No YAML config found or failed to load:", error.message);

      // This is not necessarily an error - YAML config is optional
      set({
        configLoaded: true,
        configError: undefined, // Don't treat missing YAML as an error
      });
    }
  },

  setConfigError: (error: string) => {
    set({ configError: error });
  },

  clearConfigError: () => {
    set({ configError: undefined });
  },

  resetConfig: () => {
    set(initialState);
  },
}));
