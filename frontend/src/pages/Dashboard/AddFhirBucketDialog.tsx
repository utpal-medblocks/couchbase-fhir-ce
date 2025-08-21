import React, { useState, useEffect } from "react";
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography,
  LinearProgress,
  Box,
  Stepper,
  Step,
  StepLabel,
} from "@mui/material";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import FhirConfigurationForm, {
  type FhirConfiguration,
} from "./FhirConfigurationForm";

interface AddFhirBucketDialogProps {
  open: boolean;
  onClose: () => void;
  bucketName: string;
  connectionName: string; // Need this for the backend API
  onSuccess?: () => void; // Callback when conversion completes
}

interface ConversionStatus {
  operationId: string;
  bucketName: string;
  status: "INITIATED" | "IN_PROGRESS" | "COMPLETED" | "FAILED" | "CANCELLED";
  currentStep: string;
  currentStepDescription: string;
  totalSteps: number;
  completedSteps: number;
  progressPercentage: number;
  startedAt: string;
  completedAt?: string;
  completedOperations: string[];
  errorMessage?: string;
}

const AddFhirBucketDialog: React.FC<AddFhirBucketDialogProps> = ({
  open,
  onClose,
  bucketName,
  connectionName,
  onSuccess,
}) => {
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [isConverting, setIsConverting] = useState(false);
  const [conversionStatus, setConversionStatus] =
    useState<ConversionStatus | null>(null);
  const [operationId, setOperationId] = useState<string | null>(null);
  const [activeStep, setActiveStep] = useState(0);
  const [fhirConfig, setFhirConfig] = useState<FhirConfiguration>({
    fhirRelease: "Release 4",
    profiles: [{ profile: "US Core 6.1.0", version: "" }],
    validation: {
      mode: "lenient",
      enforceUSCore: false,
      allowUnknownElements: true,
      terminologyChecks: false,
    },
    logs: {
      enableSystem: false,
      enableCRUDAudit: false,
      enableSearchAudit: false,
      rotationBy: "size",
      number: 30,
      s3Endpoint: "",
    },
  });
  const { setBucketFhirStatus } = useConnectionStore();
  const { setFhirConfig: saveFhirConfigToStore, getFhirConfig } =
    useBucketStore();

  const steps = ["Configure FHIR Settings", "Convert Bucket"];

  // Load existing FHIR config when dialog opens
  useEffect(() => {
    if (open) {
      // Try to load existing FHIR configuration from store
      const existingConfig = getFhirConfig(connectionName, bucketName);
      if (existingConfig) {
        setFhirConfig({
          fhirRelease: existingConfig.fhirRelease,
          profiles: existingConfig.profiles,
          validation: existingConfig.validation,
          logs: existingConfig.logs,
        });
      }
    } else {
      resetState();
    }
  }, [open, connectionName, bucketName, getFhirConfig]);

  // Status polling effect
  useEffect(() => {
    let interval: number;

    if (operationId && isConverting) {
      // Poll every 2 seconds for status updates
      interval = window.setInterval(async () => {
        try {
          const response = await fetch(
            `/api/admin/fhir-bucket/conversion-status/${operationId}`
          );
          if (response.ok) {
            const status: ConversionStatus = await response.json();
            setConversionStatus(status);
            setInfo(status.currentStepDescription);

            // Check if conversion is complete
            if (status.status === "COMPLETED") {
              setIsConverting(false);
              setInfo("FHIR bucket conversion completed successfully!");
              window.clearInterval(interval);

              // Update the store to mark this bucket as FHIR-enabled
              setBucketFhirStatus(bucketName, true);

              // Save the FHIR configuration to the bucket store
              saveFhirConfigToStore(connectionName, bucketName, {
                fhirRelease: fhirConfig.fhirRelease,
                profiles: fhirConfig.profiles,
                validation: fhirConfig.validation,
                logs: fhirConfig.logs,
                version: "1.0",
                description: "FHIR-enabled bucket configuration",
                createdAt: new Date().toISOString(),
              });

              if (onSuccess) {
                setTimeout(() => onSuccess(), 1000);
              }
            } else if (status.status === "FAILED") {
              setIsConverting(false);
              setError(status.errorMessage || "Conversion failed");
              setInfo(null);
              window.clearInterval(interval);
            }
          }
        } catch (err) {
          console.error("Error polling conversion status:", err);
        }
      }, 2000);
    }

    return () => {
      if (interval) {
        window.clearInterval(interval);
      }
    };
  }, [operationId, isConverting, onSuccess]);

  const resetState = () => {
    setError(null);
    setInfo(null);
    setIsConverting(false);
    setConversionStatus(null);
    setOperationId(null);
    setActiveStep(0);
  };

  const startConversion = async () => {
    setIsConverting(true);
    setError(null);
    setInfo("Initiating FHIR bucket conversion...");
    setActiveStep(1);

    try {
      const response = await fetch(
        `/api/admin/fhir-bucket/${bucketName}/convert?connectionName=${connectionName}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ fhirConfiguration: fhirConfig }),
        }
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const result = await response.json();

      if (result.operationId) {
        setOperationId(result.operationId);
        setInfo(result.message);
      } else {
        throw new Error(result.message || "Failed to start conversion");
      }
    } catch (err: any) {
      console.error("Failed to start FHIR conversion:", err);
      setError(err.message || "Failed to start conversion");
      setInfo(null);
      setIsConverting(false);
    }
  };

  const handleNext = () => {
    setActiveStep((prevActiveStep) => prevActiveStep + 1);
  };

  const handleBack = () => {
    setActiveStep((prevActiveStep) => prevActiveStep - 1);
  };

  const handleClose = () => {
    if (!isConverting) {
      resetState();
      onClose();
    }
  };

  const canClose =
    !isConverting ||
    conversionStatus?.status === "COMPLETED" ||
    conversionStatus?.status === "FAILED";

  return (
    <Dialog
      open={open}
      onClose={(_event, reason) => {
        if (reason !== "backdropClick" && canClose) {
          handleClose();
        }
      }}
      fullWidth={true}
      maxWidth={"sm"}
      disableEscapeKeyDown={!canClose}
    >
      <DialogTitle>Convert bucket "{bucketName}" to FHIR</DialogTitle>
      <DialogContent>
        {error && (
          <Alert variant="filled" severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {info && (
          <Alert variant="outlined" severity="info" sx={{ mb: 2 }}>
            {info}
          </Alert>
        )}

        {/* <Typography gutterBottom variant="body2" sx={{ mb: 2 }}>
          This action will convert <strong>{bucketName}</strong> to a FHIR
          bucket.
          <br />
          <strong>This action is irreversible.</strong>
        </Typography> */}

        {/* Stepper */}
        <Stepper activeStep={activeStep} sx={{ mb: 3 }}>
          {steps.map((label) => (
            <Step key={label}>
              <StepLabel>{label}</StepLabel>
            </Step>
          ))}
        </Stepper>

        {/* Step Content */}
        {activeStep === 0 && (
          <FhirConfigurationForm
            config={fhirConfig}
            onChange={setFhirConfig}
            disabled={isConverting}
          />
        )}

        {activeStep === 1 && (
          <Box>
            <Typography variant="body2" sx={{ mb: 2 }}>
              Ready to convert bucket with the following configuration:
            </Typography>
            <Box sx={{ display: "flex", gap: 2, mb: 2 }}>
              <Alert severity="info" sx={{ flex: 1 }}>
                <Typography variant="body2">
                  <strong>FHIR Release:</strong> {fhirConfig.fhirRelease}
                  <br />
                  <strong>Profiles:</strong>
                  <ul style={{ margin: 0, paddingLeft: 20 }}>
                    {fhirConfig.profiles.map((p, idx) => (
                      <li key={idx}>
                        {p.profile}
                        {p.version ? ` (v${p.version})` : ""}
                      </li>
                    ))}
                  </ul>
                  <strong>Validation:</strong>
                  <ul style={{ margin: 0, paddingLeft: 20 }}>
                    <li>Mode: {fhirConfig.validation.mode}</li>
                    <li>
                      Enforce US Core:{" "}
                      {fhirConfig.validation.enforceUSCore
                        ? "Enabled"
                        : "Disabled"}
                    </li>
                    <li>
                      Allow Unknown Elements:{" "}
                      {fhirConfig.validation.allowUnknownElements
                        ? "Enabled"
                        : "Disabled"}
                    </li>
                    <li>
                      Terminology Checks:{" "}
                      {fhirConfig.validation.terminologyChecks
                        ? "Enabled"
                        : "Disabled"}
                    </li>
                  </ul>
                </Typography>
              </Alert>
              <Alert severity="info" sx={{ flex: 1 }}>
                <Typography variant="body2">
                  <strong>Logs:</strong>
                  <ul style={{ margin: 0, paddingLeft: 20 }}>
                    <li>
                      Enable System Logs:{" "}
                      {fhirConfig.logs.enableSystem ? "Enabled" : "Disabled"}
                    </li>
                    <li>
                      Enable CRUD Audit:{" "}
                      {fhirConfig.logs.enableCRUDAudit ? "Enabled" : "Disabled"}
                    </li>
                    <li>
                      Enable Search Audit:{" "}
                      {fhirConfig.logs.enableSearchAudit
                        ? "Enabled"
                        : "Disabled"}
                    </li>
                    <li>Rotation By: {fhirConfig.logs.rotationBy}</li>
                    <li>Number: {fhirConfig.logs.number}</li>
                    <li>
                      S3 Endpoint: {fhirConfig.logs.s3Endpoint || "(none)"}
                    </li>
                  </ul>
                </Typography>
              </Alert>
            </Box>
          </Box>
        )}

        {/* Progress Information */}
        {conversionStatus && (
          <Box sx={{ mt: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Progress: {conversionStatus.completedSteps} of{" "}
              {conversionStatus.totalSteps} steps completed
            </Typography>

            <LinearProgress
              variant="determinate"
              value={conversionStatus.progressPercentage}
              sx={{ mb: 2, height: 8, borderRadius: 4 }}
            />

            <Typography variant="caption" color="text.secondary">
              Current step: {conversionStatus.currentStepDescription}
            </Typography>
          </Box>
        )}

        {/* Show warning when conversion is in progress */}
        {isConverting && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            Please do not close this dialog while conversion is in progress.
          </Alert>
        )}
      </DialogContent>

      <DialogActions>
        <Button
          size="small"
          onClick={handleClose}
          disabled={!canClose}
          sx={{ textTransform: "none", padding: "4px 16px" }}
        >
          {isConverting ? "Please Wait..." : "Close"}
        </Button>

        <Box sx={{ flex: "1 1 auto" }} />

        {activeStep === 0 && !isConverting && (
          <Button
            variant="contained"
            size="small"
            onClick={handleNext}
            sx={{ textTransform: "none", padding: "4px 16px" }}
          >
            Next: Review Configuration
          </Button>
        )}

        {activeStep === 1 && !isConverting && !conversionStatus && (
          <>
            <Button
              size="small"
              onClick={handleBack}
              sx={{ textTransform: "none", padding: "4px 16px", mr: 1 }}
            >
              Back
            </Button>
            <Button
              variant="contained"
              size="small"
              onClick={startConversion}
              sx={{ textTransform: "none", padding: "4px 16px" }}
            >
              Convert to FHIR
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
};

export default AddFhirBucketDialog;
