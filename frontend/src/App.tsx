import { BrowserRouter as Router } from "react-router-dom";
import {
  ThemeProvider as MuiThemeProvider,
  createTheme,
} from "@mui/material/styles";
import CssBaseline from "@mui/material/CssBaseline";
import { ConnectionProvider } from "./pages/Layout/ConnectionProvider";
import MainLayout from "./pages/Layout/MainLayout";
import AppRoutes from "./routes/AppRoutes";
import { ThemeProvider, useThemeContext } from "./contexts/ThemeContext";
import "@fontsource/roboto/300.css";
import "@fontsource/roboto/400.css";
import "@fontsource/roboto/500.css";
import "@fontsource/roboto/700.css";

function AppContent() {
  const { themeMode } = useThemeContext();

  // Create theme based on current mode
  const theme = createTheme({
    palette: {
      mode: themeMode,
    },
  });

  return (
    <MuiThemeProvider theme={theme}>
      <CssBaseline />
      <ConnectionProvider>
        <Router>
          <MainLayout>
            <AppRoutes />
          </MainLayout>
        </Router>
      </ConnectionProvider>
    </MuiThemeProvider>
  );
}

function App() {
  return (
    <ThemeProvider>
      <AppContent />
    </ThemeProvider>
  );
}

export default App;
