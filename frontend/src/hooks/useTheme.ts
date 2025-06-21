import { useState, useEffect } from "react";

export const useTheme = () => {
  const [themeMode, setThemeMode] = useState<"light" | "dark">("dark");

  // Load theme from localStorage on mount
  useEffect(() => {
    const savedTheme = localStorage.getItem("theme-mode") as "light" | "dark";
    if (savedTheme) {
      setThemeMode(savedTheme);
    } else {
      // Set default to dark and save it
      setThemeMode("dark");
      localStorage.setItem("theme-mode", "dark");
    }
  }, []);

  const toggleTheme = () => {
    const newTheme = themeMode === "light" ? "dark" : "light";
    setThemeMode(newTheme);
    localStorage.setItem("theme-mode", newTheme);
  };

  return {
    themeMode,
    toggleTheme,
  };
};
