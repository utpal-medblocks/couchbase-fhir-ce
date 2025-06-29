export interface YamlConnectionConfig {
  connection: {
    connectionString: string;
    username: string;
    password: string;
    serverType: "Server" | "Capella";
    sslEnabled: boolean;
  };
  fhir?: {
    profile: string;
    endpoint: string;
    version: string;
  };
  app?: {
    autoConnect: boolean;
    showConnectionDialog: boolean;
  };
}

export interface AppConfig {
  yamlConfig?: YamlConnectionConfig;
  configLoaded: boolean;
  configError?: string;
}
