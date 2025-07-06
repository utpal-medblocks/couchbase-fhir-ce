import React, { useState, useEffect } from "react";
import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  TextField,
  IconButton,
  Box,
  Typography,
  Switch,
  FormControlLabel,
} from "@mui/material";
import CloseIcon from "@mui/icons-material/Close";
import { Settings as SettingsIcon } from "@mui/icons-material";

interface WorkbenchConfig {
  hostname: string;
  defaultTimeout: number;
  followRedirects: boolean;
}

interface WorkbenchConfigDialogProps {
  open: boolean;
  onClose: () => void;
  onSave: (config: WorkbenchConfig) => void;
  initialConfig: WorkbenchConfig;
  selectedBucket?: string;
}

const WorkbenchConfigDialog: React.FC<WorkbenchConfigDialogProps> = ({
  open,
  onClose,
  onSave,
  initialConfig,
  selectedBucket,
}) => {
  // Form state
  const [hostname, setHostname] = useState(initialConfig.hostname);
  const [defaultTimeout, setDefaultTimeout] = useState(
    initialConfig.defaultTimeout
  );
  const [followRedirects, setFollowRedirects] = useState(
    initialConfig.followRedirects
  );

  useEffect(() => {
    if (open) {
      // Reset form to initial config when opening
      setHostname(initialConfig.hostname);
      setDefaultTimeout(initialConfig.defaultTimeout);
      setFollowRedirects(initialConfig.followRedirects);
    }
  }, [open, initialConfig]);

  const handleCancel = () => {
    // Reset to initial values
    setHostname(initialConfig.hostname);
    setDefaultTimeout(initialConfig.defaultTimeout);
    setFollowRedirects(initialConfig.followRedirects);
    onClose();
  };

  const handleSave = () => {
    const config: WorkbenchConfig = {
      hostname,
      defaultTimeout,
      followRedirects,
    };
    onSave(config);
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
        <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
          <SettingsIcon />
          Workbench Configuration
        </Box>
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
        <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
          <TextField
            fullWidth
            label="Hostname:Port"
            value={hostname}
            onChange={(e) => setHostname(e.target.value)}
            placeholder="localhost:8080"
          />
          <TextField
            fullWidth
            label="Request Timeout (ms)"
            type="number"
            value={defaultTimeout}
            onChange={(e) =>
              setDefaultTimeout(parseInt(e.target.value) || 30000)
            }
          />
          <FormControlLabel
            control={
              <Switch
                checked={followRedirects}
                onChange={(e) => setFollowRedirects(e.target.checked)}
              />
            }
            label="Follow Redirects"
          />
          <Typography variant="body2" color="text.secondary">
            Base URL: http://{hostname}/fhir/{selectedBucket || "{bucket}"}
          </Typography>
        </Box>
      </DialogContent>

      <DialogActions>
        <Button
          onClick={handleCancel}
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
        >
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={handleSave}
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
        >
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
};

export default WorkbenchConfigDialog;
