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
  Autocomplete,
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

  const connectionId = connection.connectionName;

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

  // General collection resourceTypes state
  const [generalResourceTypes, setGeneralResourceTypes] = useState<string[]>(
    []
  );
  const [selectedGeneralResourceType, setSelectedGeneralResourceType] =
    useState<string | null>(null);

  // Single-tenant mode: use the single FHIR bucket
  const bucket = bucketStore.bucket;

  // Get collections for "Resources" scope
  const collections = bucketStore.collections;
  const filteredCollections = collections
    .filter(
      (col) => col.scopeName === "Resources" && col.collectionName !== "General" // Filter out General collection
    )
    .sort((a, b) => {
      // Always put "Patient" first
      if (a.collectionName === "Patient") return -1;
      if (b.collectionName === "Patient") return 1;

      // Sort rest alphabetically
      return a.collectionName.localeCompare(b.collectionName);
    });

  // Set default bucket (single-tenant: always "fhir")
  useEffect(() => {
    if (!selectedBucket && bucket) {
      setSelectedBucket(bucket.bucketName);
    }
  }, [bucket, selectedBucket]);

  // Fetch bucket data on component mount and set up auto-refresh for collection counts
  useEffect(() => {
    if (!connection.isConnected) return;

    const refreshCollectionCounts = () => {
      console.log("🔄 Refreshing collection counts...");
      return bucketStore.fetchBucketData();
    };

    // Initial fetch
    if (!bucket) {
      console.log("🔄 Initial fetch of bucket data for collection counts...");
      refreshCollectionCounts();
    }

    // Auto-refresh collection counts every 20 seconds to catch document insertions
    const interval = setInterval(refreshCollectionCounts, 20000);

    return () => clearInterval(interval);
  }, [connection.isConnected, bucket, bucketStore]);

  // Fetch General resourceTypes on component mount
  useEffect(() => {
    const fetchGeneralResourceTypes = async () => {
      try {
        const resourceTypes =
          await fhirResourceService.getGeneralResourceTypes();
        setGeneralResourceTypes(resourceTypes);
      } catch (error) {
        console.error("Failed to fetch General resource types:", error);
      }
    };

    fetchGeneralResourceTypes();
  }, []);

  const handleChangePage = (
    _: React.MouseEvent<HTMLButtonElement> | null,
    newPage: number
  ) => {
    setPage(newPage);
    const effectiveCollection =
      selectedCollection || (selectedGeneralResourceType ? "General" : "");
    if (effectiveCollection) {
      fetchDocumentMetadata(
        effectiveCollection,
        newPage,
        rowsPerPage,
        selectedGeneralResourceType || undefined
      );
    }
  };

  const handleChangeRowsPerPage = (event: { target: { value: string } }) => {
    const newRowsPerPage = parseInt(event.target.value, 10);
    setRowsPerPage(newRowsPerPage);
    setPage(0);
    const effectiveCollection =
      selectedCollection || (selectedGeneralResourceType ? "General" : "");
    if (effectiveCollection) {
      fetchDocumentMetadata(
        effectiveCollection,
        0,
        newRowsPerPage,
        selectedGeneralResourceType || undefined
      );
    }
  };

  const handleCollectionClick = (collectionName: string) => {
    setSelectedCollection(collectionName);
    setSelectedGeneralResourceType(null); // Clear General selection
    setPage(0); // Reset to first page
    fetchDocumentMetadata(collectionName, 0, rowsPerPage);
  };

  const handleGeneralResourceTypeSelection = (resourceType: string | null) => {
    setSelectedGeneralResourceType(resourceType);
    setSelectedCollection(""); // Clear regular collection selection
    setPage(0); // Reset to first page
    if (resourceType) {
      // Fetch document metadata for General collection with specific resourceType
      fetchDocumentMetadata("General", 0, rowsPerPage, resourceType);
    } else {
      // Clear document metadata when no resourceType is selected
      setDocumentMetadata([]);
      setTotalCount(0);
    }
  };

  // Handle patient filter fetch button click
  const handlePatientFilterFetch = () => {
    // Refresh the currently selected collection with patient filter
    const effectiveCollection =
      selectedCollection || (selectedGeneralResourceType ? "General" : "");

    if (effectiveCollection) {
      // Clear document details
      setSelectedDocumentKey("");
      setSelectedDocumentMetadata(null);
      setDocumentContent(null);
      setSelectedVersions([]);

      // Reset to first page and fetch with patient filter
      setPage(0);
      fetchDocumentMetadata(
        effectiveCollection,
        0,
        rowsPerPage,
        selectedGeneralResourceType || undefined
      );
    }
  };

  // Handle clear patient filter
  const handleClearPatientFilter = () => {
    // Clear the patient ID
    setPatientId("");

    // Clear document details
    setSelectedDocumentKey("");
    setSelectedDocumentMetadata(null);
    setDocumentContent(null);
    setSelectedVersions([]);

    // Refresh the currently selected collection without patient filter
    const effectiveCollection =
      selectedCollection || (selectedGeneralResourceType ? "General" : "");

    if (effectiveCollection) {
      setPage(0);
      // Explicitly pass empty string to override the state patientId (which hasn't updated yet)
      fetchDocumentMetadata(
        effectiveCollection,
        0,
        rowsPerPage,
        selectedGeneralResourceType || undefined,
        "" // Override patientId with empty string
      );
    }
  };

  const fetchDocumentContent = async (documentKey: string) => {
    const effectiveCollection =
      selectedCollection || (selectedGeneralResourceType ? "General" : "");
    if (!selectedBucket || !effectiveCollection || !connectionId) return;

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
        collectionName: effectiveCollection,
        documentKey: documentKey,
      });

      setDocumentContent(document);

      // Extract metadata from the fetched document for version history
      if (document && document.meta) {
        // Find the Couchbase FHIR custom tag
        const customTag = document.meta.tag?.find(
          (tag: any) =>
            tag.system === "http://couchbase.fhir.com/fhir/custom-tags"
        );

        const updatedMetadata: DocumentMetadata = {
          id: document.id,
          versionId: document.meta.versionId,
          lastUpdated: document.meta.lastUpdated,
          code: customTag?.code || null,
          display: customTag?.display || null,
          deleted: false,
          isCurrentVersion: true,
        };

        setSelectedDocumentMetadata(updatedMetadata);
      }

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

  // Manual refresh function removed - collection click now handles refresh

  // fetchDocumentKeys removed - not used in component (fetchDocumentMetadata is used instead)

  const fetchDocumentMetadata = async (
    collectionName: string,
    pageNum: number,
    pageSize: number,
    resourceType?: string,
    overridePatientId?: string | null
  ) => {
    if (!selectedBucket || !connectionId) return;

    setDocumentMetadataLoading(true);
    try {
      // Use overridePatientId if explicitly provided (including empty string), otherwise use state
      const effectivePatientId =
        overridePatientId !== undefined ? overridePatientId : patientId;

      const request: any = {
        connectionName: connectionId,
        bucketName: selectedBucket,
        collectionName: collectionName,
        page: pageNum,
        pageSize: pageSize,
        patientId: effectivePatientId || undefined,
      };

      // Add resourceType for General collection
      if (
        collectionName === "General" &&
        (resourceType || selectedGeneralResourceType)
      ) {
        request.resourceType = resourceType || selectedGeneralResourceType;
      }

      const response: DocumentMetadataResponse =
        await fhirResourceService.getDocumentMetadata(request);

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
            <Typography
              variant="body2"
              sx={{ color: "primary.main", fontWeight: 600 }}
            >
              {bucket?.bucketName || "Loading..."}
            </Typography>
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
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              handlePatientFilterFetch();
            }
          }}
        />
        <Button
          variant="contained"
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
          onClick={handlePatientFilterFetch}
          disabled={!selectedCollection && !selectedGeneralResourceType}
        >
          Fetch
        </Button>
        <Button
          variant="outlined"
          size="small"
          sx={{ textTransform: "none", padding: "4px 16px" }}
          onClick={handleClearPatientFilter}
          disabled={!patientId}
        >
          Clear
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
          <TableContainer
            sx={{
              flex: 1,
              overflowY: "auto",
              scrollbarWidth: "none", // Firefox
              msOverflowStyle: "none", // IE/Edge
              "&::-webkit-scrollbar": { display: "none" }, // Chrome/Safari
            }}
          >
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

          {/* General Collection Search Selector */}
          <Box sx={{ p: 1, borderTop: 1, borderColor: "divider" }}>
            <Autocomplete
              size="small"
              options={generalResourceTypes}
              value={selectedGeneralResourceType}
              onChange={(_, newValue) =>
                handleGeneralResourceTypeSelection(newValue)
              }
              renderInput={(params) => (
                <TextField
                  {...params}
                  placeholder="Search General resources..."
                  variant="outlined"
                  size="small"
                />
              )}
              clearOnBlur={false}
              clearOnEscape
              freeSolo={false}
              filterOptions={(options, { inputValue }) =>
                options.filter((option) =>
                  option.toLowerCase().includes(inputValue.toLowerCase())
                )
              }
              noOptionsText="No matching resources"
              sx={{
                "& .MuiOutlinedInput-root": {
                  backgroundColor: selectedGeneralResourceType
                    ? "action.selected"
                    : "inherit",
                },
              }}
            />
          </Box>
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
                    // For General collection, use selected resourceType, otherwise use collection name
                    const resourceType =
                      selectedGeneralResourceType || selectedCollection;
                    const documentKey = `${resourceType}/${metadata.id}`;

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
                          <Typography variant="body2">{metadata.id}</Typography>
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
                label="Versions"
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
