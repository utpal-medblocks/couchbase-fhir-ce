import React, { useState } from "react";
import { styled, useTheme } from "@mui/material/styles";
import type { Theme, CSSObject } from "@mui/material/styles";
import { useNavigate, useLocation } from "react-router-dom";
import {
  Box,
  Drawer as MuiDrawer,
  AppBar as MuiAppBar,
  Toolbar,
  List,
  CssBaseline,
  Typography,
  Divider,
  IconButton,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Tooltip,
} from "@mui/material";
import MenuIcon from "@mui/icons-material/Menu";
import { LightMode, DarkMode } from "@mui/icons-material";
import {
  BsGear,
  BsQuestionCircle,
  BsPersonGear,
  BsJournalText,
  BsHouse,
  BsServer,
  BsClipboardData,
  BsBell,
  BsChatLeftText,
  BsTools,
  BsJournalMedical,
  BsBucket,
} from "react-icons/bs";
import { TbApiApp } from "react-icons/tb";
import { VscFlame } from "react-icons/vsc";

import CouchbaseLogo from "../../assets/icons/couchbase.png"; // Uncomment when you add the icon
import ConnectionStatus from "./ConnectionStatus";
import { useThemeContext } from "../../contexts/ThemeContext";

const drawerWidth = 200;

const openedMixin = (theme: Theme): CSSObject => ({
  width: drawerWidth,
  transition: theme.transitions.create("width", {
    easing: theme.transitions.easing.sharp,
    duration: theme.transitions.duration.enteringScreen,
  }),
  overflowX: "hidden",
});

const closedMixin = (theme: Theme): CSSObject => ({
  transition: theme.transitions.create("width", {
    easing: theme.transitions.easing.sharp,
    duration: theme.transitions.duration.leavingScreen,
  }),
  overflowX: "hidden",
  width: `calc(${theme.spacing(7)} + 1px)`,
  [theme.breakpoints.up("sm")]: {
    width: `calc(${theme.spacing(8)} + 1px)`,
  },
});

const DrawerHeader = styled("div")(({ theme }) => ({
  display: "flex",
  alignItems: "center",
  justifyContent: "flex-end",
  padding: theme.spacing(0, 1),
  minHeight: "48px",
}));

// Updated AppBar - no conditional styling based on drawer state
const AppBar = styled(MuiAppBar)(({ theme }) => ({
  zIndex: theme.zIndex.drawer + 1,
  backgroundColor:
    theme.palette.mode === "dark"
      ? theme.palette.background.paper
      : theme.palette.background.paper, // use the same as dark mode
  color: theme.palette.text.primary, // always use theme text color
  height: 48,
  minHeight: 48,
}));

const Drawer = styled(MuiDrawer, {
  shouldForwardProp: (prop) => prop !== "open",
})(({ theme, open }) => ({
  width: drawerWidth,
  flexShrink: 0,
  whiteSpace: "nowrap",
  boxSizing: "border-box",
  ...(open && {
    ...openedMixin(theme),
    "& .MuiDrawer-paper": openedMixin(theme),
  }),
  ...(!open && {
    ...closedMixin(theme),
    "& .MuiDrawer-paper": closedMixin(theme),
  }),
}));

const menuItems = [
  { id: "dashboard", label: "Dashboard", icon: BsHouse, path: "/dashboard" },
  { id: "buckets", label: "Buckets", icon: BsBucket, path: "/buckets" },
  {
    id: "fhir-resources",
    label: "FHIR Resources",
    icon: VscFlame,
    path: "/fhir-resources",
  },
];

const bottomMenuItems = [
  {
    id: "auditlogs",
    label: "Audit Logs",
    icon: BsJournalMedical,
    path: "/auditlogs",
  },
  {
    id: "systemlogs",
    label: "System Logs",
    icon: BsJournalText,
    path: "/systemlogs",
  },
];

interface MainLayoutProps {
  children: React.ReactNode;
}

export default function MainLayout({ children }: MainLayoutProps) {
  const theme = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const { themeMode, toggleTheme } = useThemeContext();

  // Local state for UI controls
  const [drawerOpen, setDrawerOpen] = useState(true);

  const toggleDrawer = () => setDrawerOpen(!drawerOpen);

  const listItemButtonStyles = {
    minHeight: 48,
    justifyContent: drawerOpen ? "initial" : "center",
    px: 2.5,
  };

  const listItemIconStyles = {
    minWidth: 0,
    mr: drawerOpen ? 3 : "auto",
    justifyContent: "center",
  };

  const handleMenuClick = (path: string) => {
    navigate(path);
  };

  // Function to check if current path matches menu item
  const isActive = (path: string) => {
    if (path === "/" && location.pathname === "/") return true;
    if (path !== "/" && location.pathname.startsWith(path)) return true;
    return false;
  };

  return (
    <Box sx={{ display: "flex", width: "100vw", minHeight: "100vh" }}>
      {/* <Box sx={{ display: "flex", width: "100%" }}> */}
      <CssBaseline />
      {/* Full width AppBar */}
      <AppBar position="fixed">
        <Toolbar
          variant="dense"
          sx={{ minHeight: 48, height: 48, paddingLeft: 1, paddingRight: 1 }}
        >
          <IconButton
            color="inherit"
            aria-label="open drawer"
            onClick={toggleDrawer}
            edge="start"
            disableRipple
            sx={{
              fontSize: "20px",
              "&:focus": {
                outline: "none",
              },
            }}
          >
            <MenuIcon />
          </IconButton>
          <Box sx={{ marginLeft: 2, marginTop: 1 }}>
            <img
              src={CouchbaseLogo}
              alt="Couchbase"
              height={32}
              style={{
                filter: themeMode === "dark" ? "brightness(1.0)" : "none",
              }}
            />
          </Box>
          <Box marginLeft={1}>
            <Typography
              variant="body2"
              sx={{ margin: 0, lineHeight: 1 }}
              color="inherit"
              component="div"
            >
              Couchbase
            </Typography>
            <Typography
              variant="h6"
              sx={{ margin: 0, lineHeight: 1 }}
              color="inherit"
              component="div"
            >
              FHIR CE
            </Typography>
          </Box>
          <ConnectionStatus />
          <Box sx={{ flexGrow: 1 }} />
          <Box sx={{ display: "flex" }}>
            <Tooltip title="Notifications" placement="bottom">
              <IconButton
                disableRipple
                sx={{
                  fontSize: "20px",
                  "&:focus": {
                    outline: "none",
                  },
                }}
              >
                <BsBell />
              </IconButton>
            </Tooltip>
            <Tooltip title="Toggle Theme" placement="bottom">
              <IconButton
                onClick={toggleTheme}
                disableRipple
                sx={{
                  fontSize: "20px",
                  "&:focus": {
                    outline: "none",
                  },
                }}
              >
                {themeMode === "dark" ? <LightMode /> : <DarkMode />}
              </IconButton>
            </Tooltip>
            <Tooltip title="Help" placement="bottom">
              <IconButton
                disableRipple
                sx={{
                  fontSize: "20px",
                  "&:focus": {
                    outline: "none",
                  },
                }}
              >
                <BsQuestionCircle />
              </IconButton>
            </Tooltip>
            <Tooltip title="Feedback" placement="bottom">
              <IconButton
                disableRipple
                sx={{
                  fontSize: "20px",
                  "&:focus": {
                    outline: "none",
                  },
                }}
              >
                <BsChatLeftText />
              </IconButton>
            </Tooltip>
            <IconButton
              disableRipple
              sx={{
                fontSize: "20px",
                "&:focus": {
                  outline: "none",
                },
              }}
            >
              <BsPersonGear />
            </IconButton>
          </Box>
        </Toolbar>
      </AppBar>

      {/* Drawer below AppBar */}
      <Drawer variant="permanent" open={drawerOpen}>
        <DrawerHeader />
        <Box display="flex" flexDirection="column" height="100%">
          <List sx={{ paddingTop: 0, paddingBottom: 0 }}>
            {menuItems.map((item) => {
              const IconComponent = item.icon;
              return (
                <ListItem
                  key={item.id}
                  disablePadding
                  sx={{
                    display: "block",
                    backgroundColor: isActive(item.path)
                      ? theme.palette.action.selected
                      : undefined,
                  }}
                  onClick={() => handleMenuClick(item.path)}
                >
                  <Tooltip
                    title={item.label}
                    placement="right"
                    disableHoverListener={drawerOpen}
                    arrow
                  >
                    <ListItemButton sx={listItemButtonStyles}>
                      <ListItemIcon sx={listItemIconStyles}>
                        <IconComponent
                          style={{
                            fontSize: "20px",
                          }}
                        />
                      </ListItemIcon>
                      <ListItemText
                        primary={item.label}
                        slots={{ primary: Typography }}
                        slotProps={{ primary: { variant: "body2" } }}
                        sx={{
                          opacity: drawerOpen ? 1 : 0,
                        }}
                      />
                    </ListItemButton>
                  </Tooltip>
                </ListItem>
              );
            })}
          </List>
          <Box flexGrow={1} />
          <List sx={{ paddingTop: 0, paddingBottom: 0 }}>
            <Divider />
            {bottomMenuItems.map((item) => {
              const IconComponent = item.icon;
              return (
                <ListItem
                  key={item.id}
                  disablePadding
                  sx={{
                    display: "block",
                    backgroundColor: isActive(item.path)
                      ? theme.palette.action.selected
                      : undefined,
                  }}
                  onClick={() => handleMenuClick(item.path)}
                >
                  <Tooltip
                    title={item.label}
                    placement="right"
                    disableHoverListener={drawerOpen}
                    arrow
                  >
                    <ListItemButton sx={listItemButtonStyles}>
                      <ListItemIcon sx={listItemIconStyles}>
                        <IconComponent
                          style={{
                            fontSize: "20px",
                          }}
                        />
                      </ListItemIcon>
                      <ListItemText
                        primary={item.label}
                        slots={{ primary: Typography }}
                        slotProps={{ primary: { variant: "body2" } }}
                        sx={{
                          opacity: drawerOpen ? 1 : 0,
                        }}
                      />
                    </ListItemButton>
                  </Tooltip>
                </ListItem>
              );
            })}
          </List>
        </Box>
      </Drawer>

      {/* Main content area with proper offset for full-width AppBar */}
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          display: "flex",
          marginTop: 6, // Space for AppBar (48px)
          padding: 0,
          marginLeft: 0,
          width: drawerOpen
            ? `calc(100% - ${drawerWidth}px)`
            : `calc(100% - ${theme.spacing(7)} - 8px)`,
          height: `calc(100vh - 48px)`,
          transition: theme.transitions.create(["margin", "width"], {
            easing: theme.transitions.easing.sharp,
            duration: theme.transitions.duration.leavingScreen,
          }),
          overflow: "hidden", // helps prevent content overflow
        }}
      >
        {children}
      </Box>
    </Box>
  );
}
