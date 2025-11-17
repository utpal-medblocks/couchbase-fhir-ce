import axios from "axios";

/**
 * Axios interceptor configuration
 * Automatically adds JWT token to all /api/admin/* requests
 */

// Request interceptor - add JWT token to admin API requests
axios.interceptors.request.use(
  (config) => {
    // Only add token to /api/admin/* endpoints
    if (config.url?.startsWith("/api/admin")) {
      // Get token from localStorage (Zustand persists it there)
      const authStorage = localStorage.getItem("auth-storage");
      
      if (authStorage) {
        try {
          const { state } = JSON.parse(authStorage);
          const token = state?.token;
          
          if (token) {
            config.headers.Authorization = `Bearer ${token}`;
          }
        } catch (error) {
          console.error("Failed to parse auth token:", error);
        }
      }
    }
    
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor - handle 401 Unauthorized (token expired/invalid)
axios.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Token is invalid or expired
      // Clear auth state and redirect to login
      localStorage.removeItem("auth-storage");
      
      // Only redirect if we're not already on the login page
      if (!window.location.pathname.includes("/login")) {
        window.location.href = "/login";
      }
    }
    
    return Promise.reject(error);
  }
);

export default axios;

