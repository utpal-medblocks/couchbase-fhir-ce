import {
  Box,
  Typography,
  FormControl,
  Select,
  MenuItem,
  TextField,
  Tab,
  Tabs,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TablePagination,
  Button,
  CircularProgress,
} from "@mui/material";
import { useState, useEffect } from "react";
import { tableHeaderStyle, tableCellStyle } from "../../styles/styles";
import React from "react";
import { useConnectionStore } from "../../store/connectionStore";
import { useBucketStore } from "../../store/bucketStore";
import fhirResourceService from "../../services/fhirResourceService";
import type {
  DocumentKeyResponse,
  DocumentMetadata,
  DocumentMetadataResponse,
} from "../../services/fhirResourceService";
import EditorComponent from "../../components/EditorComponent";
import FhirTreeView from "../../components/FhirTreeView";
import TimelineComponent from "../../components/TimelineComponent";
import BlameComponent from "../../components/BlameComponent";
import { useThemeContext } from "../../contexts/ThemeContext";
import type { VersionHistoryItem } from "../../components/TimelineComponent";

export default function FhirResources() {
  // Get stores and theme
  const connection = useConnectionStore((state) => state.connection);
  const bucketStore = useBucketStore();
  const { themeMode } = useThemeContext();

  const connectionId = connection.name;

  // State for component
  const [selectedBucket, setSelectedBucket] = useState("");
  const [patientId, setPatientId] = useState("");
  const [selectedTab, setSelectedTab] = useState(0);
  const [selectedCollection, setSelectedCollection] = useState("");

  // Document keys state
  const [documentKeys, setDocumentKeys] = useState<string[]>([]);
  const [documentKeysLoading, setDocumentKeysLoading] = useState(false);

  // Document metadata state
  const [documentMetadata, setDocumentMetadata] = useState<DocumentMetadata[]>(
    []
  );
  const [documentMetadataLoading, setDocumentMetadataLoading] = useState(false);

  // Document content state
  const [selectedDocumentKey, setSelectedDocumentKey] = useState("");
  const [selectedDocumentMetadata, setSelectedDocumentMetadata] =
    useState<DocumentMetadata | null>(null);
  const [documentContent, setDocumentContent] = useState<any>(null);
  const [documentLoading, setDocumentLoading] = useState(false);

  // Pagination state
  const [page, setPage] = React.useState(0);
  const [rowsPerPage, setRowsPerPage] = React.useState(10);
  const [totalCount, setTotalCount] = React.useState(0);

  // Version selection state for Timeline/Blame
  const [selectedVersions, setSelectedVersions] = React.useState<
    VersionHistoryItem[]
  >([]);

  // Get available buckets for FHIR (you might want to filter these)
  const availableBuckets = bucketStore.buckets[connectionId] || [];

  // Get collections for selected bucket and "Resources" scope
  const collections = bucketStore.collections[connectionId] || [];
  const filteredCollections = selectedBucket
    ? collections
        .filter(
          (col) =>
            col.bucketName === selectedBucket && col.scopeName === "Resources"
        )
        .sort((a, b) => {
          // Always put "Patient" first
          if (a.collectionName === "Patient") return -1;
          if (b.collectionName === "Patient") return 1;

          // Sort rest alphabetically
          return a.collectionName.localeCompare(b.collectionName);
        })
    : [];

  // Set default bucket if none selected and buckets are available
  useEffect(() => {
    if (!selectedBucket && availableBuckets.length > 0) {
      // You might want to filter for FHIR buckets here
      const fhirBucket =
        availableBuckets.find(
          (bucket) =>
            bucket.bucketName.toLowerCase().includes("fhir") ||
            bucket.bucketName.toLowerCase().includes("health")
        ) || availableBuckets[0];
      setSelectedBucket(fhirBucket.bucketName);
    }
  }, [availableBuckets, selectedBucket]);

  // Fetch bucket data on component mount if not already loaded
  useEffect(() => {
    const buckets = bucketStore.buckets[connectionId] || [];
    if (connectionId && buckets.length === 0) {
      // Only fetch if we don't have bucket data already
      bucketStore.fetchBucketData(connectionId);
    }
  }, [connectionId, bucketStore]);

  const handleChangePage = (
    _: React.MouseEvent<HTMLButtonElement> | null,
    newPage: number
  ) => {
    setPage(newPage);
    if (selectedCollection) {
      fetchDocumentMetadata(selectedCollection, newPage, rowsPerPage);
    }
  };

  const handleChangeRowsPerPage = (event: { target: { value: string } }) => {
    const newRowsPerPage = parseInt(event.target.value, 10);
    setRowsPerPage(newRowsPerPage);
    setPage(0);
    if (selectedCollection) {
      fetchDocumentMetadata(selectedCollection, 0, newRowsPerPage);
    }
  };

  const handleCollectionClick = (collectionName: string) => {
    setSelectedCollection(collectionName);
    setPage(0); // Reset to first page
    fetchDocumentMetadata(collectionName, 0, rowsPerPage);
  };

  const fetchDocumentContent = async (documentKey: string) => {
    if (!selectedBucket || !selectedCollection || !connectionId) return;

    setDocumentLoading(true);
    setSelectedDocumentKey(documentKey);
    // Reset version selections when switching documents
    setSelectedVersions([]);

    try {
      // console.log("🔍 Frontend: Fetching document with key:", documentKey);
      // console.log("🔍 Frontend: Request params:", {
      //   connectionName: connectionId,
      //   bucketName: selectedBucket,
      //   collectionName: selectedCollection,
      //   documentKey: documentKey,
      // });

      const document = await fhirResourceService.getDocument({
        connectionName: connectionId,
        bucketName: selectedBucket,
        collectionName: selectedCollection,
        documentKey: documentKey,
      });

      // console.log("📦 Frontend: Received document type:", typeof document);
      // console.log("📦 Frontend: Document is array:", Array.isArray(document));
      // console.log("📦 Frontend: Document content:", document);
      // console.log(
      //   "📦 Frontend: Document JSON:",
      //   JSON.stringify(document, null, 2)
      // );

      setDocumentContent(document);
      // Auto-switch to JSON tab when document is loaded
      setSelectedTab(0);
    } catch (error) {
      console.error("Failed to fetch document content:", error);
      setDocumentContent(null);
    } finally {
      setDocumentLoading(false);
    }
  };

  const handleVersionCompare = (
    version1: VersionHistoryItem,
    version2: VersionHistoryItem
  ) => {
    setSelectedVersions([version1, version2]);
    // Auto-switch to Blame tab to show the comparison
    setSelectedTab(3);
  };

  const fetchDocumentKeys = async (
    collectionName: string,
    pageNum: number,
    pageSize: number
  ) => {
    if (!selectedBucket || !connectionId) return;

    setDocumentKeysLoading(true);
    try {
      const response: DocumentKeyResponse =
        await fhirResourceService.getDocumentKeys({
          connectionName: connectionId,
          bucketName: selectedBucket,
          collectionName: collectionName,
          page: pageNum,
          pageSize: pageSize,
          patientId: patientId || undefined,
        });

      setDocumentKeys(response.documentKeys);
      setTotalCount(response.totalCount);
    } catch (error) {
      console.error("Failed to fetch document keys:", error);
      setDocumentKeys([]);
      setTotalCount(0);
    } finally {
      setDocumentKeysLoading(false);
    }
  };

  const fetchDocumentMetadata = async (
    collectionName: string,
    pageNum: number,
    pageSize: number
  ) => {
    if (!selectedBucket || !connectionId) return;

    setDocumentMetadataLoading(true);
    try {
      const response: DocumentMetadataResponse =
        await fhirResourceService.getDocumentMetadata({
          connectionName: connectionId,
          bucketName: selectedBucket,
          collectionName: collectionName,
          page: pageNum,
          pageSize: pageSize,
          patientId: patientId || undefined,
        });

      setDocumentMetadata(response.documents);
      setTotalCount(response.totalCount);
    } catch (error) {
      console.error("Failed to fetch document metadata:", error);
      setDocumentMetadata([]);
      setTotalCount(0);
    } finally {
      setDocumentMetadataLoading(false);
    }
  };

  return (
    <Box
      sx={{
        p: 1,
        height: "100%",
        display: "flex",
        flexDirection: "column",
        width: "100%",
      }}
    >
      {/* Header Section */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderBottom: 1,
          borderColor: "divider",
          pb: 1,
        }}
      >
        <Typography variant="h6">FHIR Resources</Typography>
        <Box sx={{ display: "flex", alignItems: "center", gap: 2, pr: 2 }}>
          <Typography variant="body2" sx={{ color: "primary.main" }}>
            FHIR Bucket
          </Typography>
          <FormControl
            variant="standard"
            sx={{
              minWidth: 150,
              color: "GrayText",
            }}
            size="small"
          >
            <Select
              value={selectedBucket}
              onChange={(e) => setSelectedBucket(e.target.value)}
              displayEmpty
            >
              <MenuItem value="" disabled>
                Select FHIR Bucket
              </MenuItem>
              {availableBuckets.map((bucket) => (
                <MenuItem key={bucket.bucketName} value={bucket.bucketName}>
                  {bucket.bucketName}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
      </Box>

      {/* Patient ID Search Box */}
      <Box sx={{ mb: 0.5, display: "flex", gap: 1, alignItems: "center" }}>
        <TextField
          sx={{
            width: "100%",
          }}
          variant="outlined"
          size="small"
          label="Patient ID"
          placeholder="Enter Patient ID to filter documents"
          value={patientId}
          onChange={(e) => setPatientId(e.target.value)}
        />
        <Button
          variant="contained"
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
        >
          Fetch
        </Button>
      </Box>

      {/* Main Content - Three Boxes */}
      <Box
        sx={{
          flex: 1,
          display: "flex",
          gap: 2,
          minHeight: 0,
          width: "100%",
        }}
      >
        {/* Collections Box - 20% */}
        <Box
          sx={{
            width: "20%",
            height: "100%",
            border: 1,
            borderColor: "divider",
            borderRadius: 1,
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
          }}
        >
          <TableContainer sx={{ height: "100%" }}>
            <Table stickyHeader size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Resource</TableCell>
                  <TableCell sx={tableHeaderStyle}>Docs</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filteredCollections.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={2} align="center">
                      <Typography color="textSecondary" variant="body2">
                        {selectedBucket
                          ? "No collections found"
                          : "Select a bucket"}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  filteredCollections.map((collection) => (
                    <TableRow
                      key={`${collection.bucketName}-${collection.scopeName}-${collection.collectionName}`}
                      hover
                      onClick={() =>
                        handleCollectionClick(collection.collectionName)
                      }
                      sx={{
                        cursor: "pointer",
                        backgroundColor:
                          selectedCollection === collection.collectionName
                            ? "action.selected"
                            : "inherit",
                      }}
                    >
                      <TableCell sx={tableCellStyle}>
                        {collection.collectionName}
                      </TableCell>
                      <TableCell sx={tableCellStyle} align="right">
                        {collection.items.toLocaleString()}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
        </Box>

        {/* Document IDs Box - 30% */}
        <Box
          sx={{
            width: "30%",
            height: "100%",
            border: 1,
            borderColor: "divider",
            borderRadius: 1,
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
          }}
        >
          <TableContainer sx={{ height: "100%" }}>
            <Table stickyHeader size="small">
              <TableHead>
                <TableRow>
                  <TableCell sx={tableHeaderStyle}>Resource id</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {documentMetadataLoading ? (
                  <TableRow>
                    <TableCell align="center">
                      <CircularProgress size={20} />
                    </TableCell>
                  </TableRow>
                ) : documentMetadata.length === 0 ? (
                  <TableRow>
                    <TableCell align="center">
                      <Typography color="textSecondary" variant="body2">
                        {selectedCollection
                          ? "No resource ids found"
                          : "Select a collection"}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  documentMetadata.map((metadata) => {
                    const documentKey = `${selectedCollection}/${metadata.id}`;
                    const isVersioned = parseInt(metadata.versionId) > 1;
                    const isDeleted = metadata.deleted;

                    return (
                      <TableRow
                        key={documentKey}
                        hover
                        sx={{
                          cursor: "pointer",
                          backgroundColor:
                            selectedDocumentKey === documentKey
                              ? "action.selected"
                              : "inherit",
                        }}
                        onClick={() => {
                          setSelectedDocumentMetadata(metadata);
                          fetchDocumentContent(documentKey);
                        }}
                      >
                        <TableCell sx={tableCellStyle}>
                          <Typography
                            variant="body2"
                            sx={{
                              color: isDeleted
                                ? "error.main"
                                : isVersioned
                                ? "success.main"
                                : "text.primary",
                              fontWeight:
                                isVersioned || isDeleted ? "medium" : "normal",
                            }}
                          >
                            {metadata.id}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    );
                  })
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <TablePagination
            component="div"
            count={totalCount}
            page={page}
            onPageChange={handleChangePage}
            rowsPerPage={rowsPerPage}
            onRowsPerPageChange={handleChangeRowsPerPage}
          />
        </Box>

        {/* Document Details Box - 50% */}
        <Box
          sx={{
            width: "50%",
            height: "100%",
            border: 1,
            borderColor: "divider",
            borderRadius: 1,
            overflow: "hidden",
            display: "flex",
            flexDirection: "column",
          }}
        >
          {/* Tabs Header */}
          <Box
            sx={{
              display: "flex",
              alignItems: "center",
              borderBottom: 1,
              borderColor: "divider",
              //              minHeight: 48,
            }}
          >
            <Tabs
              value={selectedTab}
              onChange={(_, newValue) => setSelectedTab(newValue)}
              sx={{ flexGrow: 1 }}
            >
              <Tab
                sx={{
                  margin: 0,
                }}
                label="JSON"
              />
              <Tab
                sx={{
                  margin: 0,
                }}
                label="FHIR"
              />
              <Tab
                sx={{
                  margin: 0,
                }}
                label="Timeline"
              />
              <Tab
                sx={{
                  margin: 0,
                }}
                label="Blame"
              />
            </Tabs>
          </Box>
          {/* Content */}
          <Box
            sx={{
              flex: 1,
              overflow: "hidden",
              display: "flex",
              flexDirection: "column",
            }}
          >
            {selectedTab === 0 && (
              <Box sx={{ flex: 1, overflow: "hidden" }}>
                {documentLoading ? (
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
                ) : documentContent ? (
                  <EditorComponent
                    value={JSON.stringify(documentContent, null, 2)}
                    language="json"
                    theme={themeMode}
                  />
                ) : (
                  <Box
                    sx={{
                      display: "flex",
                      justifyContent: "center",
                      alignItems: "center",
                      height: "100%",
                    }}
                  >
                    <Typography variant="body2" color="text.secondary">
                      Select a document id to view JSON content
                    </Typography>
                  </Box>
                )}
              </Box>
            )}
            {selectedTab === 1 && (
              <Box sx={{ flex: 1, overflow: "hidden" }}>
                {documentLoading ? (
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
                ) : documentContent ? (
                  <FhirTreeView data={documentContent} theme={themeMode} />
                ) : (
                  <Box
                    sx={{
                      display: "flex",
                      justifyContent: "center",
                      alignItems: "center",
                      height: "100%",
                    }}
                  >
                    <Typography variant="body2" color="text.secondary">
                      Select a document key to view FHIR structure
                    </Typography>
                  </Box>
                )}
              </Box>
            )}
            {selectedTab === 2 && (
              <Box sx={{ flex: 1, overflow: "hidden" }}>
                <TimelineComponent
                  documentKey={selectedDocumentKey}
                  connectionName={connectionId}
                  bucketName={selectedBucket}
                  collectionName={selectedCollection}
                  currentDocumentMetadata={
                    selectedDocumentMetadata || undefined
                  }
                  onCompare={handleVersionCompare}
                />
              </Box>
            )}
            {selectedTab === 3 && (
              <Box sx={{ flex: 1, overflow: "hidden" }}>
                <BlameComponent
                  documentKey={selectedDocumentKey}
                  connectionName={connectionId}
                  bucketName={selectedBucket}
                  collectionName={selectedCollection}
                  selectedVersions={selectedVersions}
                />
              </Box>
            )}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
