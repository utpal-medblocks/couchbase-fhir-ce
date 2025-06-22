import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import Dashboard from "../pages/Dashboard/Dashboard";
import Buckets from "../pages/Buckets/Buckets";
import FhirResources from "../pages/FHIRResources/FhirResources";
import Workbench from "../pages/Workbench/Workbench";
import AuditLogs from "../pages/AuditLogs/AuditLogs";
import SystemLogs from "../pages/SystemLogs/Logs";
import ErrorBoundary from "../components/ErrorBoundary";

const AppRoutes: React.FC = () => {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="/dashboard" element={<Dashboard />} />
      <Route
        path="/buckets"
        element={
          <ErrorBoundary>
            <Buckets />
          </ErrorBoundary>
        }
      />
      <Route
        path="/fhir-resources"
        element={
          <ErrorBoundary>
            <FhirResources />
          </ErrorBoundary>
        }
      />
      <Route
        path="/workbench"
        element={
          <ErrorBoundary>
            <Workbench />
          </ErrorBoundary>
        }
      />
      <Route
        path="/auditlogs"
        element={
          <ErrorBoundary>
            <AuditLogs />
          </ErrorBoundary>
        }
      />
      <Route
        path="/systemlogs"
        element={
          <ErrorBoundary>
            <SystemLogs />
          </ErrorBoundary>
        }
      />
      {/* Catch-all route for unmatched paths - redirect to dashboard */}
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
};

export default AppRoutes;
