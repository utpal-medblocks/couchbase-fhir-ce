import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Dashboard from "../pages/Dashboard/Dashboard";
import Buckets from "../pages/Buckets/Buckets";
import FhirResources from "../pages/FHIRResources/FhirResources";
import Workbench from "../pages/Workbench/Workbench";
import AuditLogs from "../pages/AuditLogs/AuditLogs";
import SystemLogs from "../pages/SystemLogs/Logs";

const AppRoutes: React.FC = () => {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="/dashboard" element={<Dashboard />} />
      <Route path="/buckets" element={<Buckets />} />
      <Route path="/fhir-resources" element={<FhirResources />} />
      <Route path="/workbench" element={<Workbench />} />
      <Route path="/auditlogs" element={<AuditLogs />} />
      <Route path="/systemlogs" element={<SystemLogs />} />
    </Routes>
  );
};

export default AppRoutes;
