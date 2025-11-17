import { create } from "zustand";
import { persist } from "zustand/middleware";

/**
 * User information interface
 */
export interface UserInfo {
  email: string;
  name: string;
}

/**
 * Authentication state interface
 */
interface AuthState {
  // State
  token: string | null;
  user: UserInfo | null;
  isAuthenticated: boolean;

  // Actions
  login: (token: string, user: UserInfo) => void;
  logout: () => void;
  setToken: (token: string) => void;
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

      // Login action
      login: (token: string, user: UserInfo) => {
        set({
          token,
          user,
          isAuthenticated: true,
        });
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
    }),
    {
      name: "auth-storage", // localStorage key
      partialize: (state) => ({
        token: state.token,
        user: state.user,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
);

