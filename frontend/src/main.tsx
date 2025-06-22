import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";

console.log("🚀 main.tsx: Starting application initialization");
const startTime = performance.now();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>
);

console.log(
  `✅ main.tsx: Application initialization completed in ${(
    performance.now() - startTime
  ).toFixed(2)}ms`
);
