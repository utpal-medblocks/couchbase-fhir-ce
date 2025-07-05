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
import type { DocumentKeyResponse } from "../../services/fhirResourceService";
import EditorComponent from "../../components/EditorComponent";
import FhirTreeView from "../../components/FhirTreeView";
import { useThemeContext } from "../../contexts/ThemeContext";

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

  // Document content state
  const [selectedDocumentKey, setSelectedDocumentKey] = useState("");
  const [documentContent, setDocumentContent] = useState<any>(null);
  const [documentLoading, setDocumentLoading] = useState(false);

  // Pagination state
  const [page, setPage] = React.useState(0);
  const [rowsPerPage, setRowsPerPage] = React.useState(10);
  const [totalCount, setTotalCount] = React.useState(0);

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

          // Sort rest by items count in descending order
          return b.items - a.items;
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
      fetchDocumentKeys(selectedCollection, newPage, rowsPerPage);
    }
  };

  const handleChangeRowsPerPage = (event: { target: { value: string } }) => {
    const newRowsPerPage = parseInt(event.target.value, 10);
    setRowsPerPage(newRowsPerPage);
    setPage(0);
    if (selectedCollection) {
      fetchDocumentKeys(selectedCollection, 0, newRowsPerPage);
    }
  };

  const handleCollectionClick = (collectionName: string) => {
    setSelectedCollection(collectionName);
    setPage(0); // Reset to first page
    fetchDocumentKeys(collectionName, 0, rowsPerPage);
  };

  const fetchDocumentContent = async (documentKey: string) => {
    if (!selectedBucket || !selectedCollection || !connectionId) return;

    setDocumentLoading(true);
    setSelectedDocumentKey(documentKey);

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
              "& .MuiSelect-select": {
                paddingBottom: 0,
              },
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
            "& .MuiInputBase-root-MuiOutlinedInput-root": {
              fontSize: "0.875rem",
            },
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

        {/* Document Keys Box - 30% */}
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
                  <TableCell sx={tableHeaderStyle}>Document Key</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {documentKeysLoading ? (
                  <TableRow>
                    <TableCell align="center">
                      <CircularProgress size={20} />
                    </TableCell>
                  </TableRow>
                ) : documentKeys.length === 0 ? (
                  <TableRow>
                    <TableCell align="center">
                      <Typography color="textSecondary" variant="body2">
                        {selectedCollection
                          ? "No document keys found"
                          : "Select a collection"}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  documentKeys.map((documentKey) => (
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
                        fetchDocumentContent(documentKey);
                      }}
                    >
                      <TableCell sx={tableCellStyle}>{documentKey}</TableCell>
                    </TableRow>
                  ))
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
                  textTransform: "none",
                  margin: 0,
                  "&:focus": {
                    outline: "none",
                  },
                }}
                label="JSON"
              />
              <Tab
                sx={{
                  textTransform: "none",
                  margin: 0,
                  "&:focus": {
                    outline: "none",
                  },
                }}
                label="FHIR"
              />
              <Tab
                sx={{
                  textTransform: "none",
                  margin: 0,
                  "&:focus": {
                    outline: "none",
                  },
                }}
                label="History"
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
                      Select a document key to view JSON content
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
              <Box
                sx={{
                  display: "flex",
                  justifyContent: "center",
                  alignItems: "center",
                  height: "100%",
                }}
              >
                <Typography variant="body2" color="text.secondary">
                  History view will go here
                </Typography>
              </Box>
            )}
          </Box>
        </Box>
      </Box>
    </Box>
  );
}
