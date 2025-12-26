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
  Avatar,
  Menu,
  MenuItem,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  InputAdornment,
  Button,
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
  BsPeople,
  BsKey,
  BsShieldLock,
} from "react-icons/bs";
import { TbApiApp } from "react-icons/tb";
import { VscFlame } from "react-icons/vsc";

import CouchbaseLogo from "../../assets/icons/couchbase.png"; // Uncomment when you add the icon
import ConnectionStatus from "./ConnectionStatus";
import InitializationDialog from "../../components/InitializationDialog";
import { useThemeContext } from "../../contexts/ThemeContext";
import { useAuthStore } from "../../store/authStore";
import { useBucketStore } from "../../store/bucketStore";
import { useConnectionStore } from "../../store/connectionStore";
import { BsBoxArrowRight } from "react-icons/bs";
import { updateUser } from "../../services/usersService";
import {
  Visibility as VisibilityIcon,
  VisibilityOff as VisibilityOffIcon,
} from "@mui/icons-material";

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
  {
    id: "dashboard",
    label: "Dashboard",
    icon: BsHouse,
    path: "/dashboard",
    adminOnly: false,
  },
  {
    id: "buckets",
    label: "Buckets",
    icon: BsBucket,
    path: "/buckets",
    adminOnly: false,
  },
  {
    id: "fhir-resources",
    label: "FHIR Resources",
    icon: VscFlame,
    path: "/fhir-resources",
    adminOnly: false,
  },
  {
    id: "users",
    label: "Users",
    icon: BsPeople,
    path: "/users",
    adminOnly: true,
  },
  {
    id: "bulk-groups",
    label: "Bulk Groups",
    icon: BsPeople,
    path: "/bulk-groups",
    adminOnly: true,
  },
  {
    id: "tokens",
    label: "API Tokens",
    icon: BsKey,
    path: "/tokens",
    adminOnly: false,
  },
  {
    id: "clients",
    label: "Client Registration",
    icon: TbApiApp,
    path: "/clients",
    adminOnly: false,
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
  const { logout, user } = useAuthStore();

  // Avatar menu state
  const [userMenuAnchor, setUserMenuAnchor] = useState<null | HTMLElement>(
    null
  );
  const userMenuOpen = Boolean(userMenuAnchor);
  const handleOpenUserMenu = (event: React.MouseEvent<HTMLElement>) => {
    setUserMenuAnchor(event.currentTarget);
  };
  const handleCloseUserMenu = () => setUserMenuAnchor(null);

  // Change password dialog state
  const [changePwdOpen, setChangePwdOpen] = useState(false);
  const [newPassword, setNewPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [pwdError, setPwdError] = useState<string | null>(null);
  const [pwdSubmitting, setPwdSubmitting] = useState(false);
  const handleOpenChangePwd = () => {
    setNewPassword("");
    setPwdError(null);
    setShowPassword(false);
    setChangePwdOpen(true);
  };
  const handleCloseChangePwd = () => {
    if (!pwdSubmitting) setChangePwdOpen(false);
  };
  const handleSubmitPassword = async () => {
    if (!user) return;
    if (!newPassword || newPassword.length < 8) {
      setPwdError("Password must be at least 8 characters");
      return;
    }
    try {
      setPwdSubmitting(true);
      setPwdError(null);
      // Using email as ID (created that way in user creation flow)
      await updateUser(user.email, { passwordHash: newPassword });
      setChangePwdOpen(false);
    } catch (e: any) {
      setPwdError(e?.response?.data?.error || "Failed to update password");
    } finally {
      setPwdSubmitting(false);
    }
  };

  // Derive initials from user name or email
  const getInitials = (): string => {
    if (user?.name) {
      const parts = user.name.trim().split(/\s+/);
      const initials = parts
        .slice(0, 2)
        .map((p) => p[0]?.toUpperCase())
        .join("");
      return initials || "?";
    }
    if (user?.email) {
      return user.email[0]?.toUpperCase() || "?";
    }
    return "?";
  };

  // Local state for UI controls
  const [drawerOpen, setDrawerOpen] = useState(true);
  const [initDialogOpen, setInitDialogOpen] = useState(false);

  const { connection } = useConnectionStore();
  const {
    initializationStatus,
    fetchInitializationStatus,
    fetchBucketData,
    bucket,
  } = useBucketStore();

  // Check initialization status when connected
  React.useEffect(() => {
    if (connection.isConnected) {
      fetchInitializationStatus();
    }
  }, [connection.isConnected, fetchInitializationStatus]);

  // When initialization transitions to READY and bucket data not yet loaded, fetch bucket details
  React.useEffect(() => {
    if (
      initializationStatus?.status === "READY" &&
      connection.isConnected &&
      !bucket
    ) {
      // When status becomes READY, attempt aggressive short polling (fast warm-up) until bucket appears or timeout
      let attempts = 0;
      const maxAttempts = 30; // ~60s total at 2s interval
      const interval = setInterval(() => {
        attempts++;
        fetchBucketData()
          .then((b) => {
            if (b) {
              clearInterval(interval);
              // optional: console.log("Bucket became available after", attempts, "attempts");
            }
          })
          .catch((e) => {
            // Non-fatal: keep trying
            if (attempts % 5 === 0) {
              console.warn(
                "Retry fetchBucketData attempt",
                attempts,
                e?.message || e
              );
            }
          });
        if (attempts >= maxAttempts) {
          clearInterval(interval);
        }
      }, 2000);
      return () => clearInterval(interval);
    }
  }, [
    initializationStatus?.status,
    connection.isConnected,
    bucket,
    fetchBucketData,
  ]);

  // Show initialization dialog when bucket missing or not initialized
  React.useEffect(() => {
    if (
      initializationStatus?.status === "BUCKET_MISSING" ||
      initializationStatus?.status === "BUCKET_NOT_INITIALIZED"
    ) {
      setInitDialogOpen(true);
    }
  }, [initializationStatus]);

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
            {/* User Avatar & Menu */}
            <Tooltip
              title={user?.name || user?.email || "Account"}
              placement="bottom"
            >
              <IconButton
                onClick={handleOpenUserMenu}
                disableRipple
                sx={{
                  p: 0,
                  width: 36,
                  height: 36,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  "&:focus": { outline: "none" },
                }}
              >
                <Avatar
                  sx={{
                    bgcolor:
                      theme.palette.mode === "dark"
                        ? theme.palette.primary.dark
                        : theme.palette.primary.main,
                    width: 28,
                    height: 28,
                    fontSize: 14,
                  }}
                >
                  {getInitials()}
                </Avatar>
              </IconButton>
            </Tooltip>
            <Menu
              anchorEl={userMenuAnchor}
              open={userMenuOpen}
              onClose={handleCloseUserMenu}
              anchorOrigin={{ vertical: "bottom", horizontal: "right" }}
              transformOrigin={{ vertical: "top", horizontal: "right" }}
              slotProps={{ paper: { sx: { minWidth: 180, py: 0.5 } } }}
            >
              {/* Header section inside menu */}
              <Box sx={{ px: 2, py: 1 }}>
                <Box display="flex" alignItems="center" gap={1}>
                  <Avatar
                    sx={{
                      bgcolor:
                        theme.palette.mode === "dark"
                          ? theme.palette.primary.dark
                          : theme.palette.primary.main,
                      width: 32,
                      height: 32,
                      fontSize: 14,
                    }}
                  >
                    {getInitials()}
                  </Avatar>
                  <Box>
                    <Typography
                      variant="body2"
                      sx={{ lineHeight: 1.2, fontWeight: 600 }}
                    >
                      {user?.name || user?.email || "Anonymous"}
                    </Typography>
                    {user?.email && (
                      <Typography
                        variant="caption"
                        color="text.secondary"
                        sx={{ lineHeight: 1.2 }}
                      >
                        {user.email}
                      </Typography>
                    )}
                  </Box>
                </Box>
              </Box>
              <Divider sx={{ my: 0.5 }} />
              <MenuItem
                onClick={() => {
                  handleCloseUserMenu();
                  handleOpenChangePwd();
                }}
                sx={{ fontSize: 14 }}
              >
                <Box display="flex" alignItems="center" gap={1}>
                  <BsShieldLock style={{ fontSize: 16 }} />
                  Change Password
                </Box>
              </MenuItem>
              <MenuItem
                onClick={() => {
                  handleCloseUserMenu();
                  logout();
                  navigate("/login");
                }}
                sx={{ fontSize: 14 }}
              >
                <Box display="flex" alignItems="center" gap={1}>
                  <BsBoxArrowRight style={{ fontSize: 16 }} />
                  Logout
                </Box>
              </MenuItem>
            </Menu>
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
              // Disable admin-only menu items for non-admin users
              const isDisabled = item.adminOnly && user?.role !== "admin";
              // Hide certain pages completely from developers
              const isDeveloper = user?.role === "developer";
              const shouldHide =
                isDeveloper &&
                (item.id === "dashboard" ||
                  item.id === "buckets" ||
                  item.id === "fhir-resources");

              if (shouldHide) return null;

              return (
                <ListItem
                  key={item.id}
                  disablePadding
                  sx={{
                    display: "block",
                    backgroundColor: isActive(item.path)
                      ? theme.palette.action.selected
                      : undefined,
                    opacity: isDisabled ? 0.5 : 1,
                  }}
                  onClick={() => !isDisabled && handleMenuClick(item.path)}
                >
                  <Tooltip
                    title={isDisabled ? "Admin access required" : item.label}
                    placement="right"
                    disableHoverListener={drawerOpen && !isDisabled}
                    arrow
                  >
                    <ListItemButton
                      sx={{
                        ...listItemButtonStyles,
                        cursor: isDisabled ? "not-allowed" : "pointer",
                      }}
                      disabled={isDisabled}
                    >
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
              // Hide logs from developers
              const isDeveloper = user?.role === "developer";
              if (isDeveloper) return null;

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

      {/* Initialization Dialog */}
      <InitializationDialog
        open={initDialogOpen}
        onClose={() => setInitDialogOpen(false)}
      />

      {/* Change Password Dialog */}
      <Dialog
        open={changePwdOpen}
        onClose={handleCloseChangePwd}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>Change Password</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>
            Set a new password for your account ({user?.email}).
          </Typography>
          <TextField
            label="New Password"
            type={showPassword ? "text" : "password"}
            value={newPassword}
            fullWidth
            onChange={(e) => setNewPassword(e.target.value)}
            error={!!pwdError}
            helperText={pwdError || "Minimum 8 characters"}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton
                    aria-label="toggle password visibility"
                    onClick={() => setShowPassword((p) => !p)}
                    edge="end"
                    size="small"
                  >
                    {showPassword ? <VisibilityOffIcon /> : <VisibilityIcon />}
                  </IconButton>
                </InputAdornment>
              ),
            }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseChangePwd} disabled={pwdSubmitting}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmitPassword}
            variant="contained"
            disabled={pwdSubmitting}
          >
            {pwdSubmitting ? "Updating..." : "Update Password"}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
