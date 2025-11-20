import React from "react";
import { Routes, Route, Navigate } from "react-router-dom";
import ProtectedRoute from "./guards/ProtectedRoute";
import MainLayout from "../pages/Layout/MainLayout";
import Login from "../pages/Auth/Login";
import Dashboard from "../pages/Dashboard/Dashboard";
import Buckets from "../pages/Buckets/Buckets";
import FhirResources from "../pages/FHIRResources/FhirResources";
import AuditLogs from "../pages/AuditLogs/AuditLogs";
import SystemLogs from "../pages/SystemLogs/Logs";
import Users from "../pages/Users/Users";
import Tokens from "../pages/Tokens/Tokens";

const AppRoutes: React.FC = () => {
  return (
    <Routes>
      {/* Public route - Login (no layout) */}
      <Route path="/login" element={<Login />} />

      {/* Protected routes - Admin UI (with MainLayout) */}
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <MainLayout>
              <Navigate to="/dashboard" replace />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <MainLayout>
              <Dashboard />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/buckets"
        element={
          <ProtectedRoute>
            <MainLayout>
              <Buckets />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/fhir-resources"
        element={
          <ProtectedRoute>
            <MainLayout>
              <FhirResources />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/auditlogs"
        element={
          <ProtectedRoute>
            <MainLayout>
              <AuditLogs />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/systemlogs"
        element={
          <ProtectedRoute>
            <MainLayout>
              <SystemLogs />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/users"
        element={
          <ProtectedRoute>
            <MainLayout>
              <Users />
            </MainLayout>
          </ProtectedRoute>
        }
      />
      <Route
        path="/tokens"
        element={
          <ProtectedRoute>
            <MainLayout>
              <Tokens />
            </MainLayout>
          </ProtectedRoute>
        }
      />

      {/* Catch-all route for unmatched paths - redirect to dashboard */}
      <Route
        path="*"
        element={
          <ProtectedRoute>
            <MainLayout>
              <Navigate to="/dashboard" replace />
            </MainLayout>
          </ProtectedRoute>
        }
      />
    </Routes>
  );
};

export default AppRoutes;
