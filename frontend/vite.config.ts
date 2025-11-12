import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    // emptyOutDir: true,
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
      "react-router-dom",
      "@mui/material",
      "@mui/material/styles",
      "@mui/material/CssBaseline",
      "@emotion/react",
      "@emotion/styled",
    ],
  },
  esbuild: {
    logOverride: { "this-is-undefined-in-esm": "silent" },
  },
});
