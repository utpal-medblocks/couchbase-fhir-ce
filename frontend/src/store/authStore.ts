import { create } from "zustand";
import { persist } from "zustand/middleware";

/**
 * User information interface
 */
export interface UserInfo {
  email: string;
  name: string;
  role: string;
  allowedScopes: string[];
}

/**
 * Authentication state interface
 */
interface AuthState {
  // State
  token: string | null;
  user: UserInfo | null;
  isAuthenticated: boolean;
  _hasHydrated: boolean;

  // Actions
  login: (token: string, user: UserInfo) => void;
  logout: () => void;
  setToken: (token: string) => void;
  setHasHydrated: (state: boolean) => void;
}

/**
 * Authentication store using Zustand
 * Persists token and user info to localStorage
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      // Initial state
      token: null,
      user: null,
      isAuthenticated: false,
      _hasHydrated: false,

      // Login action
      login: (token: string, user: UserInfo) => {
        console.log("💾 Storing auth in Zustand", {
          user,
          tokenLength: token.length,
        });
        set({
          token,
          user,
          isAuthenticated: true,
        });
        console.log("💾 Auth stored, will persist to localStorage");
      },

      // Logout action
      logout: () => {
        set({
          token: null,
          user: null,
          isAuthenticated: false,
        });
      },

      // Set token action
      setToken: (token: string) => {
        set({ token });
      },

      // Set hydration state
      setHasHydrated: (state: boolean) => {
        set({ _hasHydrated: state });
      },
    }),
    {
      name: "auth-storage", // localStorage key
      partialize: (state) => ({
        token: state.token,
        user: state.user,
        isAuthenticated: state.isAuthenticated,
      }),
      onRehydrateStorage: () => (state) => {
        console.log("♻️ Zustand rehydrating from localStorage...");
        if (state) {
          console.log("♻️ Rehydrated state:", {
            isAuthenticated: state.isAuthenticated,
            hasUser: !!state.user,
            hasToken: !!state.token,
          });
          state.setHasHydrated(true);
        }
      },
    }
  )
);
