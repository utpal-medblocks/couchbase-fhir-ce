import React, { useState, useEffect } from "react";
import {
  Box,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Checkbox,
  Button,
  Typography,
  CircularProgress,
  Tooltip,
} from "@mui/material";
import { tableHeaderStyle, tableCellStyle } from "../styles/styles";
import fhirResourceService from "../services/fhirResourceService";
import type { DocumentMetadata } from "../services/fhirResourceService";

export interface VersionHistoryItem {
  version: number;
  date: string;
  action: string;
  actor: string;
  isCurrentVersion?: boolean; // Track if this is the current version
  documentKey?: string; // For accessing the versioned document
}

interface TimelineComponentProps {
  documentKey: string;
  connectionName: string;
  bucketName: string;
  collectionName: string;
  currentDocumentMetadata?: DocumentMetadata; // Pass current document metadata from parent
  onCompare?: (
    version1: VersionHistoryItem,
    version2: VersionHistoryItem
  ) => void;
}

export default function TimelineComponent({
  documentKey,
  connectionName,
  bucketName,
  collectionName,
  currentDocumentMetadata,
  onCompare,
}: TimelineComponentProps) {
  const [versionHistory, setVersionHistory] = useState<VersionHistoryItem[]>(
    []
  );
  const [loading, setLoading] = useState(false);
  const [selectedVersions, setSelectedVersions] = useState<Set<number>>(
    new Set()
  );
  const [error, setError] = useState<string | null>(null);

  // Format date to friendly format
  const formatDate = (dateString: string): string => {
    try {
      const date = new Date(dateString);
      return date.toLocaleDateString("en-US", {
        year: "numeric",
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit",
        hour12: true,
      });
    } catch (error) {
      return dateString; // Return original if parsing fails
    }
  };

  useEffect(() => {
    if (documentKey && currentDocumentMetadata) {
      fetchVersionHistory();
    }
  }, [
    documentKey,
    connectionName,
    bucketName,
    collectionName,
    currentDocumentMetadata,
  ]);

  const fetchVersionHistory = async () => {
    if (!currentDocumentMetadata) return;

    setLoading(true);
    setError(null);

    try {
      const currentVersionId = parseInt(currentDocumentMetadata.versionId);

      if (currentVersionId === 1) {
        // Scenario 1: versionId == "1" - only current version exists
        const singleVersion: VersionHistoryItem = {
          version: 1,
          date: currentDocumentMetadata.lastUpdated,
          action: currentDocumentMetadata.code || "created_by",
          actor: currentDocumentMetadata.display || "user:unknown",
          isCurrentVersion: true,
        };
        setVersionHistory([singleVersion]);
      } else {
        // Scenario 2: versionId > "1" - fetch from Versions collection
        const documentId = documentKey.split("/")[1]; // Extract ID from "Patient/12345"
        const versions = await fhirResourceService.getVersionHistory(
          connectionName,
          bucketName,
          documentId
        );

        // Convert DocumentMetadata[] to VersionHistoryItem[]
        const versionHistory: VersionHistoryItem[] = versions.map(
          (version) => ({
            version: parseInt(version.versionId),
            date: version.lastUpdated,
            action: version.code || "unknown",
            actor: version.display || "user:unknown",
            isCurrentVersion: version.isCurrentVersion,
            documentKey: `${collectionName}/${version.id}/${version.versionId}`, // For versioned documents
          })
        );

        // Add current version to the list if not already included
        const hasCurrentVersion = versionHistory.some(
          (v) => v.version === currentVersionId
        );
        if (!hasCurrentVersion) {
          versionHistory.push({
            version: currentVersionId,
            date: currentDocumentMetadata.lastUpdated,
            action: currentDocumentMetadata.code || "updated_by",
            actor: currentDocumentMetadata.display || "user:unknown",
            isCurrentVersion: true,
          });
        }

        // Sort by version (ascending - oldest first)
        versionHistory.sort((a, b) => a.version - b.version);

        setVersionHistory(versionHistory);
      }
    } catch (err) {
      console.error("Failed to fetch version history:", err);
      setError("Failed to load version history");
      setVersionHistory([]);
    } finally {
      setLoading(false);
    }
  };

  const handleVersionSelect = (version: number, checked: boolean) => {
    const newSelected = new Set(selectedVersions);

    if (checked) {
      // If trying to select a third version, prevent it
      if (newSelected.size >= 2) {
        // Auto-uncheck the oldest selection (smallest version number)
        const oldestVersion = Math.min(...Array.from(newSelected));
        newSelected.delete(oldestVersion);
      }
      newSelected.add(version);
    } else {
      newSelected.delete(version);
    }

    setSelectedVersions(newSelected);
  };

  const handleCompare = () => {
    if (selectedVersions.size === 2 && onCompare) {
      const selectedArray = Array.from(selectedVersions).sort((a, b) => a - b);
      const version1 = versionHistory.find(
        (v) => v.version === selectedArray[0]
      );
      const version2 = versionHistory.find(
        (v) => v.version === selectedArray[1]
      );

      if (version1 && version2) {
        onCompare(version1, version2);
      }
    }
  };

  const isCompareEnabled = selectedVersions.size === 2;
  const canSelectMore = selectedVersions.size < 2;

  if (loading) {
    return (
      <Box
        sx={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: "100%",
        }}
      >
        <CircularProgress />
      </Box>
    );
  }

  if (error) {
    return (
      <Box
        sx={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: "100%",
        }}
      >
        <Typography variant="body2" color="error">
          {error}
        </Typography>
      </Box>
    );
  }

  if (!documentKey) {
    return (
      <Box
        sx={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: "100%",
        }}
      >
        <Typography variant="body2" color="text.secondary">
          Select a document to view version history
        </Typography>
      </Box>
    );
  }

  return (
    <Box
      sx={{
        height: "100%",
        display: "flex",
        flexDirection: "column",
      }}
    >
      {/* Compare Button */}
      <Box
        sx={{
          p: 1,
          borderBottom: 1,
          borderColor: "divider",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Typography variant="body2" color="text.secondary">
          Select 2 versions to compare
        </Typography>
        <Button
          variant="contained"
          size="small"
          disabled={!isCompareEnabled}
          onClick={handleCompare}
          sx={{ textTransform: "none" }}
        >
          Compare ({selectedVersions.size}/2)
        </Button>
      </Box>

      {/* Timeline Table */}
      <TableContainer sx={{ flex: 1, overflow: "auto" }}>
        <Table stickyHeader size="small">
          <TableHead>
            <TableRow>
              <TableCell sx={tableHeaderStyle} padding="checkbox">
                {/* Select Column Header */}
              </TableCell>
              <TableCell sx={tableHeaderStyle}>Version</TableCell>
              <TableCell sx={tableHeaderStyle}>Date</TableCell>
              <TableCell sx={tableHeaderStyle}>Action</TableCell>
              <TableCell sx={tableHeaderStyle}>Actor</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {versionHistory.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} align="center">
                  <Typography color="textSecondary" variant="body2">
                    No version history found
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              versionHistory
                .sort((a, b) => b.version - a.version) // Show newest first
                .map((item) => {
                  const isSelected = selectedVersions.has(item.version);
                  const canSelect = canSelectMore || isSelected;

                  return (
                    <TableRow
                      key={item.version}
                      hover
                      sx={{
                        backgroundColor: isSelected
                          ? "action.selected"
                          : "inherit",
                      }}
                    >
                      <TableCell padding="checkbox">
                        <Tooltip
                          title={
                            !canSelect && !isSelected
                              ? "Select only 2 versions to compare"
                              : ""
                          }
                        >
                          <span>
                            <Checkbox
                              checked={isSelected}
                              onChange={(e) =>
                                handleVersionSelect(
                                  item.version,
                                  e.target.checked
                                )
                              }
                              disabled={!canSelect && !isSelected}
                              size="small"
                            />
                          </span>
                        </Tooltip>
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        <Typography variant="body2" fontWeight="medium">
                          {item.version}
                        </Typography>
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        <Typography variant="body2">
                          {formatDate(item.date)}
                        </Typography>
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        <Typography
                          variant="body2"
                          sx={{
                            color:
                              item.action === "created_by"
                                ? "success.main"
                                : "primary.main",
                          }}
                        >
                          {item.action.replace("_", " ")}
                        </Typography>
                      </TableCell>
                      <TableCell sx={tableCellStyle}>
                        <Typography variant="body2">{item.actor}</Typography>
                      </TableCell>
                    </TableRow>
                  );
                })
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
