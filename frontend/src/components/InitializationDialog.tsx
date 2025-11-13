import React, { useEffect, useState } from "react";
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Typography,
  Box,
  CircularProgress,
  Alert,
  LinearProgress,
  Stepper,
  Step,
  StepLabel,
} from "@mui/material";
import {
  CheckCircle as CheckCircleIcon,
  Error as ErrorIcon,
  Warning as WarningIcon,
  Storage as StorageIcon,
} from "@mui/icons-material";
import { useBucketStore } from "../store/bucketStore";

interface InitializationDialogProps {
  open: boolean;
  onClose: () => void;
}

const InitializationDialog: React.FC<InitializationDialogProps> = ({
  open,
  onClose,
}) => {
  const {
    initializationStatus,
    fetchInitializationStatus,
    initializeFhirBucket,
  } = useBucketStore();

  const [initializing, setInitializing] = useState(false);
  const [initError, setInitError] = useState<string | null>(null);
  const [operationId, setOperationId] = useState<string | null>(null);
  const [operationStatus, setOperationStatus] = useState<any>(null);

  // Check status on mount
  useEffect(() => {
    if (open) {
      fetchInitializationStatus();
    }
  }, [open, fetchInitializationStatus]);

  // Poll operation status if initializing
  useEffect(() => {
    if (operationId && initializing) {
      const pollInterval = setInterval(async () => {
        try {
          const response = await fetch(
            `/api/admin/initialization/operation/${operationId}`
          );
          if (response.ok) {
            const status = await response.json();
            setOperationStatus(status);

            // Check if completed or failed
            if (status.status === "COMPLETED") {
              setInitializing(false);
              // Refresh initialization status
              await fetchInitializationStatus();
              setTimeout(() => {
                onClose();
              }, 2000);
            } else if (status.status === "FAILED") {
              setInitializing(false);
              setInitError(status.errorMessage || "Initialization failed");
            }
          }
        } catch (error) {
          console.error("Failed to poll operation status:", error);
        }
      }, 1000); // Poll every second

      return () => clearInterval(pollInterval);
    }
  }, [operationId, initializing, fetchInitializationStatus, onClose]);

  const handleInitialize = async () => {
    setInitializing(true);
    setInitError(null);

    try {
      const result = await initializeFhirBucket();
      setOperationId(result.operationId);
    } catch (error: any) {
      setInitializing(false);
      setInitError(error.message || "Failed to start initialization");
    }
  };

  const handleRefresh = () => {
    fetchInitializationStatus();
  };

  const renderStatusIcon = () => {
    if (!initializationStatus) return null;

    switch (initializationStatus.status) {
      case "READY":
        return <CheckCircleIcon color="success" sx={{ fontSize: 48 }} />;
      case "BUCKET_MISSING":
        return <StorageIcon color="warning" sx={{ fontSize: 48 }} />;
      case "BUCKET_NOT_INITIALIZED":
        return <WarningIcon color="warning" sx={{ fontSize: 48 }} />;
      case "NOT_CONNECTED":
        return <ErrorIcon color="error" sx={{ fontSize: 48 }} />;
      default:
        return null;
    }
  };

  const renderContent = () => {
    if (!initializationStatus) {
      return (
        <Box display="flex" justifyContent="center" alignItems="center" py={4}>
          <CircularProgress />
          <Typography ml={2}>Checking system status...</Typography>
        </Box>
      );
    }

    // Show initialization progress if initializing
    if (initializing && operationStatus) {
      return (
        <Box>
          <Typography variant="body1" gutterBottom>
            Initializing FHIR system...
          </Typography>
          <Box mt={2} mb={2}>
            <LinearProgress
              variant="determinate"
              value={(operationStatus.completedSteps / 9) * 100}
            />
          </Box>
          <Typography variant="body2" color="text.secondary" gutterBottom>
            Step {operationStatus.completedSteps} of 9:{" "}
            {operationStatus.currentStepDescription}
          </Typography>
          <Box mt={3}>
            <Stepper
              activeStep={operationStatus.completedSteps - 1}
              alternativeLabel
            >
              <Step>
                <StepLabel>Admin Scope</StepLabel>
              </Step>
              <Step>
                <StepLabel>Resources Scope</StepLabel>
              </Step>
              <Step>
                <StepLabel>Admin Collections</StepLabel>
              </Step>
              <Step>
                <StepLabel>Resource Collections</StepLabel>
              </Step>
              <Step>
                <StepLabel>Primary Indexes</StepLabel>
              </Step>
              <Step>
                <StepLabel>Deferred Indexes</StepLabel>
              </Step>
              <Step>
                <StepLabel>FTS Indexes</StepLabel>
              </Step>
              <Step>
                <StepLabel>GSI Indexes</StepLabel>
              </Step>
              <Step>
                <StepLabel>Mark as FHIR</StepLabel>
              </Step>
            </Stepper>
          </Box>
        </Box>
      );
    }

    // Show error if initialization failed
    if (initError) {
      return (
        <Box>
          <Alert severity="error" sx={{ mb: 2 }}>
            {initError}
          </Alert>
          <Typography variant="body2">
            Please check the logs and try again, or contact support if the issue
            persists.
          </Typography>
        </Box>
      );
    }

    // Show status-specific content
    switch (initializationStatus.status) {
      case "READY":
        return (
          <Box>
            <Alert severity="success" sx={{ mb: 2 }}>
              ✅ FHIR system is fully initialized and ready!
            </Alert>
            <Typography variant="body2" color="text.secondary">
              Bucket: <strong>{initializationStatus.bucketName}</strong>
            </Typography>
          </Box>
        );

      case "BUCKET_MISSING":
        return (
          <Box>
            <Alert severity="warning" sx={{ mb: 2 }}>
              {initializationStatus.message}
            </Alert>
            <Typography variant="body1" gutterBottom>
              <strong>Manual Setup Required</strong>
            </Typography>
            <Typography variant="body2" component="div" sx={{ mt: 2 }}>
              Please create the FHIR bucket manually:
            </Typography>
            <Box sx={{ mt: 2, ml: 2 }}>
              <Typography variant="body2" component="div">
                1. Open Couchbase Web Console
              </Typography>
              <Typography variant="body2" component="div">
                2. Navigate to <strong>Buckets</strong> section
              </Typography>
              <Typography variant="body2" component="div">
                3. Click <strong>Add Bucket</strong>
              </Typography>
              <Typography variant="body2" component="div">
                4. Set bucket name to: <strong>fhir</strong>
              </Typography>
              <Typography variant="body2" component="div">
                5. Configure RAM quota (recommended: 1024 MB minimum)
              </Typography>
              <Typography variant="body2" component="div">
                6. Click <strong>Add Bucket</strong>
              </Typography>
              <Typography variant="body2" component="div" sx={{ mt: 1 }}>
                7. Return here and click <strong>Refresh Status</strong>
              </Typography>
            </Box>
          </Box>
        );

      case "BUCKET_NOT_INITIALIZED":
        return (
          <Box>
            <Alert severity="info" sx={{ mb: 2 }}>
              {initializationStatus.message}
            </Alert>
            <Typography variant="body2" gutterBottom>
              The <strong>{initializationStatus.bucketName}</strong> bucket
              exists but needs to be initialized with FHIR scopes, collections,
              and indexes.
            </Typography>
            <Typography variant="body2" sx={{ mt: 2 }}>
              Click <strong>Initialize</strong> to automatically create:
            </Typography>
            <Box sx={{ mt: 1, ml: 2 }}>
              <Typography variant="body2">
                • Admin and Resources scopes
              </Typography>
              <Typography variant="body2">
                • FHIR resource collections
              </Typography>
              <Typography variant="body2">
                • Full-text search indexes
              </Typography>
              <Typography variant="body2">
                • GSI indexes for authentication
              </Typography>
            </Box>
          </Box>
        );

      case "NOT_CONNECTED":
        return (
          <Box>
            <Alert severity="error" sx={{ mb: 2 }}>
              {initializationStatus.message}
            </Alert>
            <Typography variant="body2">
              Please verify your{" "}
              <code
                style={{
                  backgroundColor: "#f5f5f5",
                  padding: "2px 6px",
                  borderRadius: 4,
                }}
              >
                config.yaml
              </code>{" "}
              file and restart the backend service.
            </Typography>
          </Box>
        );

      default:
        return null;
    }
  };

  const renderActions = () => {
    if (!initializationStatus) return null;

    if (initializing) {
      return (
        <Button onClick={() => setInitializing(false)} disabled>
          Initializing...
        </Button>
      );
    }

    switch (initializationStatus.status) {
      case "READY":
        return (
          <Button onClick={onClose} variant="contained" color="primary">
            Continue
          </Button>
        );

      case "BUCKET_MISSING":
        return (
          <Button onClick={handleRefresh} variant="contained" color="primary">
            Refresh Status
          </Button>
        );

      case "BUCKET_NOT_INITIALIZED":
        return (
          <Button
            onClick={handleInitialize}
            variant="contained"
            color="primary"
            startIcon={<CheckCircleIcon />}
          >
            Initialize
          </Button>
        );

      case "NOT_CONNECTED":
        return (
          <Button onClick={onClose} variant="outlined">
            Close
          </Button>
        );

      default:
        return null;
    }
  };

  const canDismiss =
    initializationStatus?.status === "READY" ||
    initializationStatus?.status === "NOT_CONNECTED";

  return (
    <Dialog
      open={open}
      onClose={canDismiss ? onClose : undefined}
      maxWidth="md"
      fullWidth
      disableEscapeKeyDown={!canDismiss}
    >
      <DialogTitle>
        <Box display="flex" alignItems="center" gap={2}>
          {renderStatusIcon()}
          <Typography variant="h6">FHIR System Initialization</Typography>
        </Box>
      </DialogTitle>
      <DialogContent>{renderContent()}</DialogContent>
      <DialogActions>{renderActions()}</DialogActions>
    </Dialog>
  );
};

export default InitializationDialog;
