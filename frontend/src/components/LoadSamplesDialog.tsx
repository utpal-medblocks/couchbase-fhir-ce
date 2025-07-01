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

interface LoadSamplesDialogProps {
  open: boolean;
  onClose: () => void;
  bucketName: string;
  connectionName: string;
  onSuccess?: () => void;
}

interface SampleLoadStatus {
  totalFiles: number;
  processedFiles: number;
  currentFile: string;
  resourcesLoaded: number;
  patientsLoaded: number;
  percentComplete: number;
  status: "INITIATED" | "IN_PROGRESS" | "COMPLETED" | "ERROR" | "CANCELLED";
  message: string;
}

const LoadSamplesDialog: React.FC<LoadSamplesDialogProps> = ({
  open,
  onClose,
  bucketName,
  connectionName,
  onSuccess,
}) => {
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [loadStatus, setLoadStatus] = useState<SampleLoadStatus | null>(null);

  // Cleanup when dialog closes
  useEffect(() => {
    if (!open) {
      resetState();
    }
  }, [open]);

  // For now, we'll use the simple load endpoint rather than SSE
  // This can be enhanced later to use real-time updates

  const resetState = () => {
    setError(null);
    setInfo(null);
    setIsLoading(false);
    setLoadStatus(null);
  };

  const startSampleLoad = async () => {
    setIsLoading(true);
    setError(null);
    setInfo("Initiating Synthea sample data loading...");

    try {
      // First, initiate the loading process with POST
      const response = await fetch("/api/sample-data/load-with-progress", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          connectionName: connectionName,
          bucketName: bucketName,
          overwriteExisting: false,
        }),
      });

      if (!response.ok) {
        throw new Error(`Failed to start loading: ${response.status}`);
      }

      // The response should be an SSE stream
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();

      if (!reader) {
        throw new Error("No response body available");
      }

      // Read the SSE stream
      const readStream = async () => {
        try {
          while (true) {
            const { done, value } = await reader.read();

            if (done) {
              break;
            }

            const chunk = decoder.decode(value);
            const lines = chunk.split("\n");

            for (const line of lines) {
              if (line.startsWith("data: ")) {
                try {
                  const progressData = line.substring(6);
                  if (progressData.trim()) {
                    const progress = JSON.parse(progressData);
                    console.log("Progress update:", progress);

                    // Update progress state
                    setLoadStatus(progress);

                    if (progress.message) {
                      setInfo(progress.message);
                    }

                    // Handle completion
                    if (progress.status === "COMPLETED") {
                      setIsLoading(false);
                      setInfo(
                        progress.message ||
                          "Synthea sample data loaded successfully!"
                      );

                      if (onSuccess) {
                        setTimeout(() => onSuccess(), 1000);
                      }
                      return; // Exit the loop
                    } else if (progress.status === "ERROR") {
                      setIsLoading(false);
                      setError(
                        progress.message || "Failed to load sample data"
                      );
                      setInfo(null);
                      return; // Exit the loop
                    }
                  }
                } catch (parseError) {
                  console.error("Failed to parse progress data:", parseError);
                }
              }
            }
          }
        } catch (streamError) {
          console.error("Stream reading error:", streamError);
          setError("Connection error during sample data loading");
          setInfo(null);
          setIsLoading(false);
        }
      };

      // Start reading the stream
      readStream();
    } catch (err: any) {
      console.error("Failed to start sample data loading:", err);
      setError(err.message || "Failed to start sample data loading");
      setInfo(null);
      setIsLoading(false);
    }
  };

  const handleClose = () => {
    if (!isLoading) {
      resetState();
      onClose();
    }
  };

  const canClose =
    !isLoading ||
    loadStatus?.status === "COMPLETED" ||
    loadStatus?.status === "ERROR";

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
      <DialogTitle>Load Synthea Sample Data</DialogTitle>
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
          This will load Synthea-generated sample FHIR data into{" "}
          <strong>{bucketName}</strong> bucket.
          <br />
          <strong>Sample includes:</strong> 15 patients with 22 different FHIR
          resource types.
        </Typography>

        {/* Progress Information */}
        {loadStatus && (
          <Box sx={{ mt: 2 }}>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              Progress: {loadStatus.processedFiles} of {loadStatus.totalFiles}{" "}
              files processed
            </Typography>

            <LinearProgress
              variant="determinate"
              value={loadStatus.percentComplete}
              sx={{ mb: 2, height: 8, borderRadius: 4 }}
            />

            <Typography variant="caption" color="text.secondary">
              Current file: {loadStatus.currentFile || "Processing..."}
            </Typography>

            {loadStatus.resourcesLoaded > 0 && (
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ mt: 1 }}
              >
                Resources loaded: {loadStatus.resourcesLoaded.toLocaleString()}
              </Typography>
            )}

            {loadStatus.patientsLoaded > 0 && (
              <Typography
                variant="caption"
                color="text.secondary"
                display="block"
                sx={{ mt: 1 }}
              >
                Patients loaded: {loadStatus.patientsLoaded.toLocaleString()}
              </Typography>
            )}
          </Box>
        )}

        {/* Show warning when loading is in progress */}
        {isLoading && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            Please do not close this dialog while sample data is loading.
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
          {isLoading ? "Please Wait..." : "Close"}
        </Button>

        {!isLoading && !loadStatus && (
          <Button
            variant="contained"
            size="small"
            onClick={startSampleLoad}
            sx={{ textTransform: "none", padding: "4px 16px" }}
          >
            Load Sample Data
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
};

export default LoadSamplesDialog;
