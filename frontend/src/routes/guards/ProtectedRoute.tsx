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
  const logout = useAuthStore((state) => state.logout);

  // Force login mechanism: if URL contains ?forceLogin=1 then clear any persisted auth
  // and always redirect to /login even if a token exists. Helpful for testing or demos.
  const forceLogin =
    typeof window !== "undefined" &&
    new URLSearchParams(window.location.search).get("forceLogin") === "1";

  if (forceLogin) {
    // Remove persisted storage and reset in-memory state
    try {
      localStorage.removeItem("auth-storage");
    } catch (e) {
      // ignore
    }
    if (isAuthenticated) {
      logout();
    }
    // Navigate immediately; `replace` prevents stacking history entries
    return <Navigate to="/login" replace />;
  }

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
