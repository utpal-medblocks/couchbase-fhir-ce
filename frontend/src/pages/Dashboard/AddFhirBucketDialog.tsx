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
} from "@mui/material";
import { useConnectionStore } from "../../store/connectionStore";

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
  const { setBucketFhirStatus } = useConnectionStore();

  // Cleanup when dialog closes
  useEffect(() => {
    if (!open) {
      resetState();
    }
  }, [open]);

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
  };

  const startConversion = async () => {
    setIsConverting(true);
    setError(null);
    setInfo("Initiating FHIR bucket conversion...");

    try {
      const response = await fetch(
        `/api/admin/fhir-bucket/${bucketName}/convert?connectionName=${connectionName}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({}),
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
      <DialogTitle>Convert to FHIR Bucket</DialogTitle>
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

        <Typography gutterBottom variant="body2" sx={{ mb: 2 }}>
          This action will convert <strong>{bucketName}</strong> to a FHIR
          bucket.
          <br />
          <strong>This action is irreversible.</strong>
        </Typography>

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

        {!isConverting && !conversionStatus && (
          <Button
            variant="contained"
            size="small"
            onClick={startConversion}
            sx={{ textTransform: "none", padding: "4px 16px" }}
          >
            Convert to FHIR
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
};

export default AddFhirBucketDialog;
