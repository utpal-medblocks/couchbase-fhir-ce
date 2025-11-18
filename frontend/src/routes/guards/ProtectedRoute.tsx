import { Navigate } from "react-router-dom";
import { useAuthStore } from "../../store/authStore";

/**
 * Protected Route wrapper component
 * Redirects to login if user is not authenticated
 * Waits for Zustand to hydrate from localStorage before checking auth
 */
interface ProtectedRouteProps {
  children: React.ReactNode;
}

export default function ProtectedRoute({ children }: ProtectedRouteProps) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const hasHydrated = useAuthStore((state) => state._hasHydrated);

  console.log("🛡️ ProtectedRoute check:", { hasHydrated, isAuthenticated });

  // Wait for store to hydrate from localStorage before checking auth
  if (!hasHydrated) {
    console.log("⏳ Waiting for hydration...");
    return null; // Or return a loading spinner
  }

  if (!isAuthenticated) {
    console.log("🚫 Not authenticated, redirecting to /login");
    return <Navigate to="/login" replace />;
  }

  console.log("✅ Authenticated, rendering protected content");
  return <>{children}</>;
}

