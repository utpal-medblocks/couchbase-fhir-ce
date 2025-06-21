import { create } from "zustand";

interface AppState {
  drawerOpen: boolean;
  themeMode: "light" | "dark";
  setDrawerOpen: (open: boolean) => void;
  toggleDrawer: () => void;
  toggleTheme: () => void;
}

export const useAppStore = create<AppState>((set) => ({
  drawerOpen: true,
  themeMode: "dark",
  setDrawerOpen: (open: boolean) => set({ drawerOpen: open }),
  toggleDrawer: () => set((state) => ({ drawerOpen: !state.drawerOpen })),
  toggleTheme: () =>
    set((state) => ({
      themeMode: state.themeMode === "dark" ? "light" : "dark",
    })),
}));
