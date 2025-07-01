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
      // First, make a POST request to start the loading process
      const startResponse = await fetch("/api/sample-data/load-with-progress", {
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

      if (!startResponse.ok) {
        throw new Error(`Failed to start loading: ${startResponse.status}`);
      }

      // Parse the SSE stream using EventSource-like parsing
      const reader = startResponse.body?.getReader();
      if (!reader) {
        throw new Error("No response body available");
      }

      const decoder = new TextDecoder();
      let buffer = "";

      let currentLoadStatus: SampleLoadStatus | null = null;

      const processSSEData = async () => {
        try {
          while (true) {
            const { done, value } = await reader.read();

            if (done) {
              // console.log("SSE stream completed");
              // If we reach here without COMPLETED status, show success
              if (
                currentLoadStatus?.status !== "COMPLETED" &&
                currentLoadStatus?.status !== "ERROR"
              ) {
                setIsLoading(false);
                setInfo("Synthea sample data loaded successfully!");
                if (onSuccess) {
                  setTimeout(() => {
                    onSuccess();
                    setTimeout(() => handleClose(), 1500);
                  }, 1000);
                } else {
                  setTimeout(() => handleClose(), 2000);
                }
              }
              break;
            }

            // Decode chunk and add to buffer
            const chunk = decoder.decode(value, { stream: true });
            buffer += chunk;
            // console.log("Received chunk:", JSON.stringify(chunk));

            // Process complete SSE messages (split by double newlines)
            const messages = buffer.split("\n\n");
            buffer = messages.pop() || ""; // Keep incomplete message in buffer

            for (const message of messages) {
              if (message.trim() === "") continue;

              // console.log("Processing SSE message:", JSON.stringify(message));

              // Parse SSE message format
              const lines = message.split("\n");
              let eventType = "";
              let data = "";

              for (const line of lines) {
                if (line.startsWith("event:")) {
                  eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                  data = line.substring(5).trim();
                }
              }

              // console.log("Parsed event:", eventType, "data:", data);

              // Handle progress events
              if (eventType === "progress" && data) {
                try {
                  const progress = JSON.parse(data) as SampleLoadStatus;
                  // console.log("Progress update:", progress);

                  // Update both state and local variable
                  currentLoadStatus = progress;
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
                      setTimeout(() => {
                        onSuccess();
                        setTimeout(() => handleClose(), 1500);
                      }, 1000);
                    } else {
                      setTimeout(() => handleClose(), 2000);
                    }
                    return;
                  } else if (progress.status === "ERROR") {
                    setIsLoading(false);
                    setError(progress.message || "Failed to load sample data");
                    setInfo(null);
                    return;
                  }
                } catch (parseError) {
                  console.error(
                    "Failed to parse progress data:",
                    parseError,
                    "Raw data:",
                    data
                  );
                }
              } else if (eventType === "error" && data) {
                try {
                  const errorData = JSON.parse(data);
                  setIsLoading(false);
                  setError(errorData.message || "Failed to load sample data");
                  setInfo(null);
                  return;
                } catch (parseError) {
                  console.error("Failed to parse error data:", parseError);
                }
              } else {
                // console.log("Unknown or empty event:", eventType, data);
              }
            }
          }
        } catch (streamError) {
          console.error("Stream reading error:", streamError);
          setError("Connection error during sample data loading");
          setInfo(null);
          setIsLoading(false);
        } finally {
          reader.releaseLock();
        }
      };

      await processSSEData();
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
          <strong>Sample includes:</strong> 15 patients with 20 different FHIR
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
