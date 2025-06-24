import React, { useState, useEffect } from "react";
import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
  IconButton,
  InputAdornment,
  FormControl,
  InputLabel,
  OutlinedInput,
  RadioGroup,
  FormControlLabel,
  Radio,
  Alert,
  Box,
  Typography,
  Checkbox,
} from "@mui/material";
import { Visibility, VisibilityOff } from "@mui/icons-material";
import CloseIcon from "@mui/icons-material/Close";

import { formStyles, RadioStyle } from "../../styles/styles";

interface UnifiedConnectionDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess: (connectionRequest: {
    connectionString: string;
    username: string;
    password: string;
    serverType: "Server" | "Capella";
    sslEnabled: boolean;
  }) => Promise<{ success: boolean; error?: string }>;
}

interface ConnectionRequest {
  connectionString: string;
  username: string;
  password: string;
  serverType: "Server" | "Capella";
  sslEnabled: boolean;
}

type ServerType = "Server" | "Capella";

const UnifiedConnectionDialog: React.FC<UnifiedConnectionDialogProps> = ({
  open,
  onClose,
  onSuccess,
}) => {
  // Form state
  const [serverType, setServerType] = useState<ServerType>("Server");
  const [endpoint, setEndpoint] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);

  // Server-specific fields
  const [useSSL, setUseSSL] = useState(false);
  const [certificate, setCertificate] = useState("");

  // UI state
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (open) {
      // Reset form when opening
      resetForm();
    }
  }, [open]);

  const resetForm = () => {
    setServerType("Server");
    setEndpoint("");
    setUsername("");
    setPassword("");
    setUseSSL(false);
    setCertificate("");
    setError(null);
    setInfo(null);
    setLoading(false);
    setShowPassword(false);
  };

  const handleTogglePassword = () => {
    setShowPassword(!showPassword);
  };

  const handleServerTypeChange = (
    event: React.ChangeEvent<HTMLInputElement>
  ) => {
    const newServerType = event.target.value as ServerType;
    setServerType(newServerType);

    // Reset server-specific fields when switching types
    if (newServerType === "Capella") {
      setUseSSL(false);
      setCertificate("");
    }
    setEndpoint(""); // Clear endpoint so user enters correct format
  };

  const handleSSLChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setUseSSL(event.target.checked);
    if (!event.target.checked) {
      setCertificate("");
    }
  };

  const handleCancel = () => {
    resetForm();
    onClose();
  };

  const isButtonEnabled = () => {
    const basicFields = endpoint && username && password;

    // For server with SSL, certificate is required
    if (serverType === "Server" && useSSL) {
      return basicFields && certificate;
    }

    return basicFields;
  };

  const handleSave = async () => {
    if (!isButtonEnabled()) {
      setError("Please fill in all required fields");
      return;
    }

    setLoading(true);
    setInfo("Creating connection...");
    setError(null);

    try {
      // Build connection string based on server type
      let connectionString: string;
      let sslEnabled: boolean;

      if (serverType === "Server") {
        const protocol = useSSL ? "couchbases" : "couchbase";
        connectionString = `${protocol}://${endpoint}`;
        sslEnabled = useSSL;
      } else {
        // Capella always uses SSL
        connectionString = `couchbases://${endpoint}`;
        sslEnabled = true;
      }

      const connectionRequest: ConnectionRequest = {
        connectionString,
        username,
        password,
        serverType,
        sslEnabled,
      };

      // Pass the connection request to the parent component
      const result = await onSuccess(connectionRequest);

      if (result.success) {
        setInfo("Connection request sent!");
        setTimeout(() => {
          onClose();
          resetForm();
        }, 1000);
      } else {
        setError(result.error || "Failed to create connection");
        setInfo(null);
      }
    } catch (err: any) {
      //      console.error("Connection creation error:", err);
      setError("Failed to create connection");
      setInfo(null);
    } finally {
      setLoading(false);
    }
  };

  // Returns helper text for the Endpoint field based on server type
  const getEndpointHelpText = () => {
    if (serverType === "Server") {
      return "hostname or IP address without the port number";
    } else {
      return "Omit the leading 'couchbases://' and start from cb.xxx";
    }
  };

  return (
    <Dialog
      open={open}
      onClose={(_reason) => {
        if (_reason !== "backdropClick") {
          handleCancel();
        }
      }}
      fullWidth={true}
      maxWidth={"sm"}
    >
      <DialogTitle
        sx={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          pr: 1,
        }}
      >
        Create Couchbase Connection
        <IconButton
          aria-label="close"
          onClick={handleCancel}
          size="small"
          sx={{ ml: 2 }}
        >
          <CloseIcon />
        </IconButton>
      </DialogTitle>
      <DialogContent>
        {error && (
          <Alert
            variant="filled"
            severity="error"
            onClose={() => setError(null)}
          >
            {error}
          </Alert>
        )}
        {info && (
          <Alert variant="outlined" severity="info">
            {info}
          </Alert>
        )}

        {/* Server Type Selection */}
        <Box
          component="fieldset"
          sx={{
            borderWidth: "1px",
            borderColor: "#9e9e9e",
            fontSize: ".875rem",
            mb: 2,
          }}
        >
          <legend>Connection Type</legend>
          <FormControl sx={{ width: "100%" }}>
            <RadioGroup
              row
              value={serverType}
              onChange={handleServerTypeChange}
            >
              <FormControlLabel
                sx={{
                  "& .MuiFormControlLabel-label": { fontSize: ".875rem" },
                }}
                value="Server"
                control={<Radio sx={RadioStyle} />}
                label="Self Managed"
                disabled={loading}
              />
              <FormControlLabel
                sx={{
                  "& .MuiFormControlLabel-label": { fontSize: ".875rem" },
                }}
                value="Capella"
                control={<Radio sx={RadioStyle} />}
                label="Capella"
                disabled={loading}
              />
            </RadioGroup>
          </FormControl>
        </Box>

        {/* Server Details */}
        <Box
          component="fieldset"
          sx={{
            borderWidth: "1px",
            borderColor: "#9e9e9e",
            fontSize: ".875rem",
            mb: 2,
          }}
        >
          <legend>Server Details</legend>
          <TextField
            label="Endpoint"
            fullWidth
            variant="outlined"
            size="small"
            helperText={getEndpointHelpText()}
            sx={formStyles}
            value={endpoint}
            onChange={(e) => setEndpoint(e.target.value)}
            disabled={loading}
          />
        </Box>

        {/* Authentication */}
        <Box
          component="fieldset"
          sx={{
            borderWidth: "1px",
            borderColor: "#9e9e9e",
            fontSize: ".875rem",
            mb: 2,
          }}
        >
          <legend>Authentication</legend>

          <TextField
            label="Username"
            fullWidth
            variant="outlined"
            size="small"
            sx={formStyles}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            disabled={loading}
          />

          <FormControl
            fullWidth
            variant="outlined"
            size="small"
            sx={formStyles}
          >
            <InputLabel>Password</InputLabel>
            <OutlinedInput
              type={showPassword ? "text" : "password"}
              endAdornment={
                <InputAdornment position="end">
                  <IconButton
                    onClick={handleTogglePassword}
                    edge="end"
                    size="small"
                  >
                    {showPassword ? <Visibility /> : <VisibilityOff />}
                  </IconButton>
                </InputAdornment>
              }
              label="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={loading}
            />
          </FormControl>

          {/* SSL Options - Only for Server type */}
          {serverType === "Server" && (
            <>
              <FormControlLabel
                sx={{
                  ...formStyles,
                  "& .MuiFormControlLabel-label": { fontSize: ".875rem" },
                }}
                control={
                  <Checkbox
                    checked={useSSL}
                    onChange={handleSSLChange}
                    size="small"
                    disabled={loading}
                  />
                }
                label="Use SSL"
              />

              {useSSL && (
                <TextField
                  label="SSL Certificate"
                  fullWidth
                  multiline
                  rows={4}
                  variant="outlined"
                  size="small"
                  helperText="SSL certificate content (required for SSL connections)"
                  sx={formStyles}
                  value={certificate}
                  onChange={(e) => setCertificate(e.target.value)}
                  disabled={loading}
                  required
                />
              )}
            </>
          )}
        </Box>
      </DialogContent>

      <DialogActions>
        <Button
          onClick={handleCancel}
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
          disabled={loading}
        >
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={handleSave}
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
          disabled={!isButtonEnabled() || loading}
        >
          {loading ? "Connecting..." : "Connect"}
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default UnifiedConnectionDialog;
