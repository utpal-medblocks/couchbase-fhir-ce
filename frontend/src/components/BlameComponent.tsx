import React, { useState, useEffect } from "react";
import { Box, Typography, CircularProgress } from "@mui/material";
import { DiffEditor } from "@monaco-editor/react";
import { useThemeContext } from "../contexts/ThemeContext";
import type { VersionHistoryItem } from "./TimelineComponent";
import fhirResourceService from "../services/fhirResourceService";

interface BlameComponentProps {
  documentKey: string;
  connectionName: string;
  bucketName: string;
  collectionName: string;
  selectedVersions: VersionHistoryItem[];
}

interface DiffResult {
  olderJson: string;
  newerJson: string;
  fromVersion: number;
  toVersion: number;
}

export default function BlameComponent({
  documentKey,
  connectionName,
  bucketName,
  collectionName,
  selectedVersions,
}: BlameComponentProps) {
  const [diffResult, setDiffResult] = useState<DiffResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { themeMode } = useThemeContext();

  useEffect(() => {
    if (selectedVersions.length === 2 && documentKey) {
      fetchVersionsAndGenerateDiff();
    } else {
      setDiffResult(null);
    }
  }, [
    selectedVersions,
    documentKey,
    connectionName,
    bucketName,
    collectionName,
  ]);

  const fetchVersionsAndGenerateDiff = async () => {
    setLoading(true);
    setError(null);

    try {
      // Sort versions to ensure consistent ordering (older first, newer second)
      const sortedVersions = [...selectedVersions].sort(
        (a, b) => a.version - b.version
      );
      const [olderVersion, newerVersion] = sortedVersions;

      // Construct document keys based on version source
      const getDocumentKeyForVersion = (version: VersionHistoryItem) => {
        // Extract base document ID from documentKey (e.g., "Patient/1003" -> "1003")
        const baseDocumentId = documentKey.split("/")[1];

        if (version.isCurrentVersion) {
          // Current version is in main collection (e.g., Patient/1003)
          return documentKey;
        } else {
          // Historical version is in Versions collection (e.g., Patient/1003/1)
          return `${collectionName}/${baseDocumentId}/${version.version}`;
        }
      };

      const olderDocKey = getDocumentKeyForVersion(olderVersion);
      const newerDocKey = getDocumentKeyForVersion(newerVersion);

      // Fetch both documents
      const olderDoc = await fhirResourceService.getDocument({
        connectionName,
        bucketName,
        collectionName: olderVersion.isCurrentVersion
          ? collectionName
          : "Versions",
        documentKey: olderDocKey,
      });

      const newerDoc = await fhirResourceService.getDocument({
        connectionName,
        bucketName,
        collectionName: newerVersion.isCurrentVersion
          ? collectionName
          : "Versions",
        documentKey: newerDocKey,
      });

      // Prepare JSON strings for diff editor
      const olderJson = JSON.stringify(olderDoc, null, 2);
      const newerJson = JSON.stringify(newerDoc, null, 2);

      setDiffResult({
        olderJson,
        newerJson,
        fromVersion: olderVersion.version,
        toVersion: newerVersion.version,
      });
    } catch (err) {
      console.error("Failed to fetch versions for diff:", err);
      setError("Failed to generate diff");
    } finally {
      setLoading(false);
    }
  };

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
          Select a document to view version comparison
        </Typography>
      </Box>
    );
  }

  if (selectedVersions.length !== 2) {
    return (
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          height: "100%",
          gap: 1,
        }}
      >
        <Typography variant="body2" color="text.secondary">
          {selectedVersions.length === 0
            ? "Select 2 versions in the Timeline tab to compare"
            : selectedVersions.length === 1
            ? "Select one more version to compare (need 2 total)"
            : "Select exactly 2 versions in the Timeline tab to compare"}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          Selected: {selectedVersions.length}/2 versions
        </Typography>
        {selectedVersions.length === 1 && (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ fontStyle: "italic" }}
          >
            Note: Single version documents cannot be compared
          </Typography>
        )}
      </Box>
    );
  }

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

  if (!diffResult) {
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
          Loading comparison...
        </Typography>
      </Box>
    );
  }

  const sortedVersions = [...selectedVersions].sort(
    (a, b) => a.version - b.version
  );

  return (
    <Box
      sx={{
        height: "100%",
        display: "flex",
        flexDirection: "column",
      }}
    >
      {/* Header */}
      <Box
        sx={{
          p: 1,
          borderBottom: 1,
          borderColor: "divider",
          backgroundColor: "background.paper",
        }}
      >
        <Typography variant="body2" color="text.secondary">
          Comparing version {sortedVersions[0].version} →{" "}
          {sortedVersions[1].version}
        </Typography>
      </Box>

      {/* Diff Content */}
      <Box sx={{ flex: 1, overflow: "hidden" }}>
        <DiffEditor
          original={diffResult.olderJson}
          modified={diffResult.newerJson}
          language="json"
          theme={themeMode === "dark" ? "vs-dark" : "vs"}
          options={{
            readOnly: true,
            minimap: { enabled: false },
            wordWrap: "on",
            renderSideBySide: false,
            scrollBeyondLastLine: false,
            automaticLayout: true,
          }}
        />
      </Box>
    </Box>
  );
}
