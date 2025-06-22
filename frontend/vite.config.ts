import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../../../target/classes/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080",
      "/fhir": "http://localhost:8080",
    },
    hmr: {
      overlay: false,
    },
    watch: {
      usePolling: false,
    },
  },
  optimizeDeps: {
    include: [
      "react",
      "react-dom",
      "@mui/material",
      "@emotion/react",
      "@emotion/styled",
    ],
  },
  esbuild: {
    logOverride: { "this-is-undefined-in-esm": "silent" },
  },
});
