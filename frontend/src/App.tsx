import { BrowserRouter as Router } from "react-router-dom";
import {
  ThemeProvider as MuiThemeProvider,
  createTheme,
} from "@mui/material/styles";
import CssBaseline from "@mui/material/CssBaseline";
import { DisplayProvider } from "./pages/Layout/DisplayContext";
import MainLayout from "./pages/Layout/MainLayout";
import AppRoutes from "./routes/AppRoutes";
import { ThemeProvider, useThemeContext } from "./contexts/ThemeContext";

function AppContent() {
  //  console.log("AppContent: Starting to render");
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
      <DisplayProvider>
        <Router>
          <MainLayout>
            <AppRoutes />
          </MainLayout>
        </Router>
      </DisplayProvider>
    </MuiThemeProvider>
  );
}

function App() {
  //  console.log("App: Component starting to render");
  return (
    <ThemeProvider>
      <AppContent />
    </ThemeProvider>
  );
}

export default App;
