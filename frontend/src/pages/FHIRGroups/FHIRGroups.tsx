import React, { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { decodeIntent, encodeIntent } from "../../utils/intent";
import {
  Box,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
  Tooltip,
  CircularProgress,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  Alert,
  Chip,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import RefreshIcon from "@mui/icons-material/Refresh";
import groupService, {
  type GroupRequest,
  type GroupResponse,
  type PreviewResponse,
} from "../../services/groupService";
import { useThemeContext } from "../../contexts/ThemeContext";

// Supported resource types for Group membership
const RESOURCE_TYPES = [
  "Patient",
  "Practitioner",
  "PractitionerRole",
  "RelatedPerson",
  "Device",
  "Medication",
  "Substance",
  "Group",
];

// Example filters for each resource type
const FILTER_EXAMPLES: Record<string, string[]> = {
  Patient: [
    "family=Smith",
    "birthdate=ge1987-01-01&birthdate=le1987-12-31",
    "identifier=http://hospital.smarthealthit.org|103270",
    "gender=female",
  ],
  Practitioner: [
    "name=Johnson",
    "identifier=http://hl7.org/fhir/sid/us-npi|1234567890",
  ],
  PractitionerRole: ["specialty=207R00000X"],
  RelatedPerson: ["name=Doe"],
  Device: ["status=active"],
  Medication: ["status=active"],
  Substance: ["status=active"],
  Group: ["type=person"],
};

const FHIRGroups: React.FC = () => {
  const [groups, setGroups] = useState<GroupResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [intent, setIntent] = useState<any>(null);
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { themeMode } = useThemeContext();

  // Dialog state
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogStep, setDialogStep] = useState<
    "filter" | "preview" | "confirm"
  >("filter");
  const [submitting, setSubmitting] = useState(false);
  const [dialogError, setDialogError] = useState<string>("");

  // Form state
  const [groupName, setGroupName] = useState("");
  const [resourceType, setResourceType] = useState("Patient");
  const [filter, setFilter] = useState("");

  // Preview state
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const [previewing, setPreviewing] = useState(false);

  // Load groups on mount
  useEffect(() => {
    loadGroups();

    // Handle intent from query params (e.g., from Client Registration)
    const q = new URLSearchParams(window.location.search);
    const enc = q.get("intent");
    if (enc) {
      const decoded = decodeIntent(enc);
      if (decoded) {
        setIntent(decoded as any);
        q.delete("intent");
        navigate({ pathname: location.pathname, search: q.toString() }, {
          replace: true,
        } as any);

        if (decoded.intent_type === "new_bulk_group" && decoded.modalOpen) {
          openCreateDialog();
        }
        if (decoded.intent_type === "highlight_bulk_group" && decoded.groupId) {
          setHighlightId(decoded.groupId as string);
          // Scroll to highlighted row after render
          setTimeout(() => {
            const el = document.querySelector(
              `[data-group-id="${decoded.groupId}"]`
            );
            if (el) {
              el.scrollIntoView({ behavior: "smooth", block: "center" });
            }
          }, 100);
        }
      }
    }
  }, []);

  const location = useLocation();

  const loadGroups = async () => {
    try {
      setLoading(true);
      const data = await groupService.getAll();
      setGroups(data);
      setError(null);
    } catch (err: any) {
      setError(err?.response?.data?.error || "Failed to load FHIR groups");
    } finally {
      setLoading(false);
    }
  };

  const openCreateDialog = () => {
    setDialogOpen(true);
    setDialogStep("filter");
    setGroupName("");
    setResourceType("Patient");
    setFilter("");
    setPreview(null);
    setDialogError("");
  };

  const closeDialog = () => {
    setDialogOpen(false);
    setDialogStep("filter");
    setGroupName("");
    setResourceType("Patient");
    setFilter("");
    setPreview(null);
    setDialogError("");
    setSubmitting(false);
  };

  const handlePreview = async () => {
    try {
      setPreviewing(true);
      setDialogError("");
      const result = await groupService.preview({ resourceType, filter });
      setPreview(result);
      setDialogStep("preview");
    } catch (err: any) {
      setDialogError(err?.response?.data?.error || "Failed to execute preview");
    } finally {
      setPreviewing(false);
    }
  };

  const handleCreate = async () => {
    if (!groupName.trim()) {
      setDialogError("Group name is required");
      return;
    }

    try {
      setSubmitting(true);
      setDialogError("");

      const request: GroupRequest = {
        name: groupName,
        resourceType,
        filter,
        createdBy: "admin", // TODO: Get from auth context
      };

      const created = await groupService.create(request);

      closeDialog();

      // Handle intent response (redirect back to calling page)
      if (intent && intent.intent_type === "new_bulk_group") {
        const respPayload = {
          action: "bulk_group_created",
          clientId: intent.clientId,
          group: created,
        };
        const encResp = encodeIntent(respPayload);
        const target = intent.sourcePath || "/clients";
        navigate(`${target}?intent_response=${encResp}`);
        return;
      }

      loadGroups();
    } catch (err: any) {
      setDialogError(err?.response?.data?.error || "Failed to create group");
    } finally {
      setSubmitting(false);
    }
  };

  const handleRefresh = async (groupId: string) => {
    try {
      await groupService.refresh(groupId);
      loadGroups();
    } catch (err: any) {
      setError(err?.response?.data?.error || "Failed to refresh group");
    }
  };

  const handleDelete = async (group: GroupResponse) => {
    if (!group?.id) {
      setError("Invalid group id");
      return;
    }

    const ok = confirm(`Delete FHIR group "${group.name}"?`);
    if (!ok) return;

    try {
      setDeletingId(group.id);
      await groupService.remove(group.id);
      loadGroups();
    } catch (err: any) {
      setError(err?.response?.data?.error || "Failed to delete group");
    } finally {
      setDeletingId(null);
    }
  };

  const backToFilter = () => {
    setDialogStep("filter");
    setPreview(null);
  };

  return (
    <Box sx={{ p: 2, width: "100%" }}>
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 2,
        }}
      >
        <Typography variant="h6">FHIR Groups</Typography>
        <Button
          startIcon={<AddIcon />}
          variant="contained"
          onClick={openCreateDialog}
        >
          Create FHIR Group
        </Button>
      </Box>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Card>
        <CardContent>
          {loading ? (
            <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
              <CircularProgress />
            </Box>
          ) : groups.length === 0 ? (
            <Typography>No FHIR groups found.</Typography>
          ) : (
            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    <TableCell>Resource Type</TableCell>
                    <TableCell>Filter</TableCell>
                    <TableCell align="right">Members</TableCell>
                    <TableCell>Created By</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {groups.map((g) => (
                    <TableRow
                      key={g.id}
                      hover
                      data-group-id={g.id}
                      sx={{
                        backgroundColor:
                          highlightId === g.id ? "action.selected" : undefined,
                      }}
                    >
                      <TableCell>{g.name}</TableCell>
                      <TableCell>{g.resourceType}</TableCell>
                      <TableCell>
                        <Typography
                          variant="body2"
                          sx={{
                            maxWidth: 300,
                            overflow: "hidden",
                            textOverflow: "ellipsis",
                            whiteSpace: "nowrap",
                          }}
                        >
                          {g.filter}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">{g.memberCount}</TableCell>
                      <TableCell>{g.createdBy}</TableCell>
                      <TableCell align="right">
                        <Tooltip title="Refresh (re-run filter)">
                          <IconButton
                            size="small"
                            onClick={() => handleRefresh(g.id)}
                          >
                            <RefreshIcon />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title="Delete">
                          <IconButton
                            size="small"
                            onClick={() => handleDelete(g)}
                            disabled={deletingId === g.id}
                          >
                            {deletingId === g.id ? (
                              <CircularProgress size={18} color="inherit" />
                            ) : (
                              <DeleteIcon />
                            )}
                          </IconButton>
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Create Group Dialog */}
      <Dialog open={dialogOpen} onClose={closeDialog} maxWidth="md" fullWidth>
        <DialogTitle>Create FHIR Group</DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
            {dialogError && (
              <Alert severity="error" onClose={() => setDialogError("")}>
                {dialogError}
              </Alert>
            )}

            {dialogStep === "filter" && (
              <>
                <TextField
                  label="Group Name"
                  value={groupName}
                  onChange={(e) => setGroupName(e.target.value)}
                  fullWidth
                  required
                  helperText="A descriptive name for this group"
                />

                <FormControl fullWidth>
                  <InputLabel>Resource Type</InputLabel>
                  <Select
                    value={resourceType}
                    label="Resource Type"
                    onChange={(e) => {
                      setResourceType(e.target.value);
                      setFilter(""); // Clear filter when changing type
                    }}
                  >
                    {RESOURCE_TYPES.map((type) => (
                      <MenuItem key={type} value={type}>
                        {type}
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>

                <TextField
                  label="FHIR Search Filter"
                  value={filter}
                  onChange={(e) => setFilter(e.target.value)}
                  fullWidth
                  multiline
                  rows={3}
                  placeholder={`Example: ${
                    FILTER_EXAMPLES[resourceType]?.[0] || ""
                  }`}
                  helperText="FHIR search parameters (e.g., family=Smith&birthdate=ge1987)"
                />

                <Box>
                  <Typography variant="subtitle2" gutterBottom>
                    Examples for {resourceType}:
                  </Typography>
                  {FILTER_EXAMPLES[resourceType]?.map((example, idx) => (
                    <Chip
                      key={idx}
                      label={example}
                      size="small"
                      sx={{ mr: 1, mb: 1 }}
                      onClick={() => setFilter(example)}
                    />
                  ))}
                </Box>
              </>
            )}

            {dialogStep === "preview" && preview && (
              <>
                <Alert severity="info">
                  Found <strong>{preview.totalCount}</strong> matching{" "}
                  {preview.resourceType} resources
                </Alert>

                <Typography variant="subtitle2">
                  Sample Results (showing first {preview.sampleResources.length}
                  ):
                </Typography>

                <TableContainer sx={{ maxHeight: 400 }}>
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>ID</TableCell>
                        <TableCell>Name</TableCell>
                        {preview.resourceType === "Patient" && (
                          <>
                            <TableCell>Birth Date</TableCell>
                            <TableCell>Gender</TableCell>
                          </>
                        )}
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {preview.sampleResources.map((resource) => (
                        <TableRow key={resource.id}>
                          <TableCell>{resource.id}</TableCell>
                          <TableCell>{resource.name || "(no name)"}</TableCell>
                          {preview.resourceType === "Patient" && (
                            <>
                              <TableCell>{resource.birthDate || "-"}</TableCell>
                              <TableCell>{resource.gender || "-"}</TableCell>
                            </>
                          )}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              </>
            )}
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeDialog} disabled={submitting || previewing}>
            Cancel
          </Button>
          {dialogStep === "filter" && (
            <Button
              onClick={handlePreview}
              variant="contained"
              disabled={previewing || !resourceType}
            >
              {previewing ? "Previewing..." : "Preview"}
            </Button>
          )}
          {dialogStep === "preview" && (
            <>
              <Button onClick={backToFilter} disabled={submitting}>
                Back
              </Button>
              <Button
                onClick={handleCreate}
                variant="contained"
                disabled={submitting || !groupName.trim()}
              >
                {submitting ? "Creating..." : "Create Group"}
              </Button>
            </>
          )}
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default FHIRGroups;
