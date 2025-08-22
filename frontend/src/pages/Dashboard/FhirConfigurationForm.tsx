import React from "react";
import {
  Box,
  Card,
  CardContent,
  Typography,
  FormControl,
  FormControlLabel,
  FormLabel,
  RadioGroup,
  Radio,
  Switch,
  TextField,
  Select,
  MenuItem,
  InputLabel,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  Divider,
} from "@mui/material";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";

export interface FhirConfiguration {
  fhirRelease: string;
  validation: {
    mode: "strict" | "lenient" | "disabled";
    profile: "none" | "us-core";
  };
  logs: {
    enableSystem: boolean;
    enableCRUDAudit: boolean;
    enableSearchAudit: boolean;
    rotationBy: "size" | "days";
    number: number;
    s3Endpoint: string;
  };
}

interface FhirConfigurationFormProps {
  config: FhirConfiguration;
  onChange: (config: FhirConfiguration) => void;
  disabled?: boolean;
}

const FhirConfigurationForm: React.FC<FhirConfigurationFormProps> = ({
  config,
  onChange,
  disabled = false,
}) => {
  const updateConfig = (updates: Partial<FhirConfiguration>) => {
    onChange({ ...config, ...updates });
  };

  const updateValidation = (
    updates: Partial<FhirConfiguration["validation"]>
  ) => {
    updateConfig({
      validation: { ...config.validation, ...updates },
    });
  };

  const updateLogs = (updates: Partial<FhirConfiguration["logs"]>) => {
    updateConfig({
      logs: { ...config.logs, ...updates },
    });
  };

  return (
    <Box sx={{ mt: 2 }}>
      {/* Basic FHIR Configuration */}
      <Card variant="outlined" sx={{ mb: 1 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            FHIR Configuration
          </Typography>

          <FormControl fullWidth disabled={disabled} sx={{ mb: 2 }}>
            <InputLabel>FHIR Release</InputLabel>
            <Select
              disabled={true}
              value={config.fhirRelease}
              label="FHIR Release"
              onChange={(e) => updateConfig({ fhirRelease: e.target.value })}
            >
              <MenuItem value="Release 4">FHIR R4</MenuItem>
            </Select>
          </FormControl>
        </CardContent>
      </Card>

      {/* Validation Configuration */}
      <Card variant="outlined" sx={{ mb: 1 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Validation Settings
          </Typography>

          <FormControl component="fieldset" disabled={disabled}>
            <FormLabel component="legend">Validation Mode</FormLabel>
            <RadioGroup
              value={config.validation.mode}
              onChange={(e) =>
                updateValidation({ mode: e.target.value as any })
              }
              sx={{ mb: 1 }}
            >
              <FormControlLabel
                value="strict"
                control={<Radio />}
                label={
                  <Box>
                    <Typography variant="body2">
                      <strong>Strict</strong> - Reject any validation errors
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      Recommended for production environments
                    </Typography>
                  </Box>
                }
              />
              <FormControlLabel
                value="lenient"
                control={<Radio />}
                label={
                  <Box>
                    <Typography variant="body2">
                      <strong>Lenient</strong> - Log warnings but continue
                      (default)
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      Good for development and testing
                    </Typography>
                  </Box>
                }
              />
              <FormControlLabel
                value="disabled"
                control={<Radio />}
                label={
                  <Box>
                    <Typography variant="body2">
                      <strong>Disabled</strong> - Skip validation entirely
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      Maximum performance for trusted data sources
                    </Typography>
                  </Box>
                }
              />
            </RadioGroup>
          </FormControl>

          {config.validation.mode !== "disabled" && (
            <Box sx={{ mt: 2 }}>
              <FormControl fullWidth disabled={disabled}>
                <InputLabel>Profile</InputLabel>
                <Select
                  value={config.validation.profile}
                  label="Profile"
                  onChange={(e) =>
                    updateValidation({
                      profile: e.target.value as "none" | "us-core",
                    })
                  }
                >
                  <MenuItem value="none">
                    <Box>
                      <Typography variant="body2">
                        <strong>None</strong> - Basic FHIR R4 validation
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        Standard FHIR R4 compliance only
                      </Typography>
                    </Box>
                  </MenuItem>
                  <MenuItem value="us-core">
                    <Box>
                      <Typography variant="body2">
                        <strong>US Core 6.1.0</strong> - Enforce US Core
                        profiles
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        Includes terminology validation and US Core constraints
                      </Typography>
                    </Box>
                  </MenuItem>
                </Select>
              </FormControl>
            </Box>
          )}
        </CardContent>
      </Card>

      {/* Advanced Configuration */}
      <Accordion>
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Typography variant="h6">Advanced Settings (Optional)</Typography>
        </AccordionSummary>
        <AccordionDetails>
          {/* Logging Configuration */}
          <Typography variant="subtitle1" gutterBottom>
            Audit & Logging
          </Typography>

          <Box sx={{ mb: 1 }}>
            <FormControlLabel
              control={
                <Switch
                  size="small"
                  checked={config.logs.enableCRUDAudit}
                  onChange={(e) =>
                    updateLogs({ enableCRUDAudit: e.target.checked })
                  }
                  disabled={disabled}
                />
              }
              label="Enable CRUD operation audit logs"
            />
            <br />
            <FormControlLabel
              control={
                <Switch
                  size="small"
                  checked={config.logs.enableSearchAudit}
                  onChange={(e) =>
                    updateLogs({ enableSearchAudit: e.target.checked })
                  }
                  disabled={disabled}
                />
              }
              label="Enable search operation audit logs"
            />
            <br />
            <FormControlLabel
              control={
                <Switch
                  size="small"
                  checked={config.logs.enableSystem}
                  onChange={(e) =>
                    updateLogs({ enableSystem: e.target.checked })
                  }
                  disabled={disabled}
                />
              }
              label="Enable system logs"
            />
          </Box>

          <Divider sx={{ my: 1 }} />

          {/* Log Rotation Settings */}
          <Typography variant="subtitle1" gutterBottom>
            Log Rotation (Future Feature)
          </Typography>

          <Box sx={{ display: "flex", gap: 2, mb: 1 }}>
            <FormControl sx={{ minWidth: 120 }} disabled={true}>
              <InputLabel>Rotate by</InputLabel>
              <Select
                value={config.logs.rotationBy}
                label="Rotate by"
                onChange={(e) =>
                  updateLogs({ rotationBy: e.target.value as any })
                }
              >
                <MenuItem value="size">Size</MenuItem>
                <MenuItem value="days">Days</MenuItem>
              </Select>
            </FormControl>

            <TextField
              label={config.logs.rotationBy === "size" ? "Size (GB)" : "Days"}
              type="number"
              value={config.logs.number}
              onChange={(e) =>
                updateLogs({ number: parseInt(e.target.value) || 30 })
              }
              disabled={true}
              sx={{ minWidth: 120 }}
            />
          </Box>

          <TextField
            fullWidth
            label="S3 Endpoint for Log Archival (Optional)"
            value={config.logs.s3Endpoint}
            onChange={(e) => updateLogs({ s3Endpoint: e.target.value })}
            disabled={true}
            helperText="Future feature for log archival"
            sx={{ mb: 1 }}
          />
        </AccordionDetails>
      </Accordion>
    </Box>
  );
};

export default FhirConfigurationForm;
