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
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import DeleteIcon from "@mui/icons-material/Delete";
import EditIcon from "@mui/icons-material/Edit";
import bulkGroupService, { type BulkGroupRequest, type BulkGroupResponse } from "../../services/bulkGroupService";
import axios from "../../config/axiosConfig";
import Chip from "@mui/material/Chip";
import { useThemeContext } from "../../contexts/ThemeContext";

const BulkGroups: React.FC = () => {
  const [groups, setGroups] = useState<BulkGroupResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [intent, setIntent] = useState<any>(null);
  const [highlightId, setHighlightId] = useState<string | null>(null);
  const [highlightCount, setHighlightCount] = useState<number>(10);
  const rowRefs = React.useRef<Record<string, HTMLElement | null>>({});
  const navigate = useNavigate();

  const [dialogOpen, setDialogOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // form
  const [id, setId] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  type SelectedResource = { id: string; name: string };
  const [selectedPatients, setSelectedPatients] = useState<SelectedResource[]>([]);

  // patient search
  const [resourceType, setResourceType] = useState<string>("Patient");
  const [patientQuery, setPatientQuery] = useState("");
  const [patientFamily, setPatientFamily] = useState("");
  const [patientGiven, setPatientGiven] = useState("");
  const [patientIdentifier, setPatientIdentifier] = useState("");
  const [advancedFilter, setAdvancedFilter] = useState("");
  const [patientResults, setPatientResults] = useState<any[]>([]);
  const [patientSearching, setPatientSearching] = useState(false);
  const [patientIdFetch, setPatientIdFetch] = useState("");
  const [nextUrl, setNextUrl] = useState<string | null>(null);
  const [prevUrl, setPrevUrl] = useState<string | null>(null);
  
  const location = useLocation();
  const { themeMode } = useThemeContext();

  // Ensure CSS variables for flash animation are set according to app theme
  useEffect(() => {
    try {
      const root = document.documentElement;
      if (themeMode === "light") {
        root.style.setProperty("--flash-start", "#f6feff");
        root.style.setProperty("--flash-end", "#ffffff");
      } else {
        // dark mode: subtle translucent highlights
        root.style.setProperty("--flash-start", "rgba(255,255,255,0.06)");
        root.style.setProperty("--flash-end", "rgba(255,255,255,0.02)");
      }
    } catch (e) {
      // ignore in non-browser environments
    }
  }, [themeMode]);

  useEffect(() => {
    loadGroups();
    // decode intent from query if present
    const q = new URLSearchParams(window.location.search);
    const enc = q.get('intent');
    if (enc) {
      const decoded = decodeIntent(enc);
      if (decoded) {
        setIntent(decoded as any);
        q.delete('intent');
        navigate({ pathname: location.pathname, search: q.toString() }, { replace: true } as any);
        // If another page requested we open the "new bulk group" dialog, do so
        if (decoded.intent_type === 'new_bulk_group' && decoded.modalOpen) {
          // openCreate is defined below; calling it will open the create dialog
          openCreate();
        }
        // If caller requested highlighting a specific group, prepare to flash it
        if (decoded.intent_type === 'highlight_bulk_group' && decoded.groupId) {
          const gid = decoded.groupId as string;
          const flashCount = decoded.flashCount || 10;
          setHighlightId(gid);
          setHighlightCount(flashCount);
          // scroll to row after a short delay to allow render
          setTimeout(() => {
            const el = rowRefs.current[gid];
            if (el && typeof el.scrollIntoView === 'function') {
              el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
            // clear highlight after animation duration completes
            const totalMs = (flashCount || 10) * 250 + 200;
            setTimeout(() => setHighlightId(null), totalMs);
          }, 250);
        }
      }
    }
  }, []);

  const loadGroups = async () => {
    try {
      setLoading(true);
      const data = await bulkGroupService.getAll();
      setGroups(data);
    } catch (err: any) {
      setError(err?.response?.data?.error || "Failed to load bulk groups");
    } finally {
      setLoading(false);
    }
  };

  const [editingId, setEditingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const openCreate = () => {
    setEditingId(null);
    setId("");
    setName("");
    setDescription("");
    setSelectedPatients([]);
    setPatientResults([]);
    setDialogOpen(true);
  };

  const resetPatientSearchInputs = () => {
    setPatientIdFetch("");
    setPatientQuery("");
    setPatientFamily("");
    setPatientGiven("");
    setPatientIdentifier("");
    setAdvancedFilter("");
    setPatientResults([]);
    setNextUrl(null);
    setPrevUrl(null);
    setPatientSearching(false);
  };

  const closeDialog = () => {
    setDialogOpen(false);
    // reset form and search inputs
    setEditingId(null);
    setId("");
    setName("");
    setDescription("");
    setSelectedPatients([]);
    resetPatientSearchInputs();
  };

  const openEdit = (g: BulkGroupResponse) => {
    setEditingId(g.id);
    setId(g.id);
    setName(g.name || "");
    setDescription(g.description || "");
    setSelectedPatients((g.patientIds || []).map((pid) => {
      const names = g.patientNames || {};
      const nameFromPlain = names[pid];
      const nameFromPrefixed = names[`Patient/${pid}`];
      const displayName = nameFromPlain ?? nameFromPrefixed ?? pid;
      return { id: pid, name: displayName };
    }));
    setPatientResults([]);
    setDialogOpen(true);
  };

  const handleSearchPatients = async (url?: string) => {
    if (
      !patientQuery &&
      !patientFamily &&
      !patientGiven &&
      !patientIdentifier &&
      !(advancedFilter && advancedFilter.trim()) &&
      !url
    )
      return;

    try {
      setPatientSearching(true);
      let q = "";
      if (url) {
        // paging URL provided
      } else if (advancedFilter && advancedFilter.trim()) {
        q = advancedFilter.trim().startsWith("?") ? advancedFilter.trim().substring(1) : advancedFilter.trim();
        if (!q.includes("_count")) q = q.length > 0 ? `${q}&_count=50` : `_count=50`;
      } else {
        const parts: string[] = [];
        if (patientQuery) parts.push(`name=${encodeURIComponent(patientQuery)}`);
        if (patientFamily) parts.push(`family=${encodeURIComponent(patientFamily)}`);
        if (patientGiven) parts.push(`given=${encodeURIComponent(patientGiven)}`);
        if (patientIdentifier) parts.push(`identifier=${encodeURIComponent(patientIdentifier)}`);
        parts.push(`_count=50`);
        q = parts.join("&");
      }

      const resp = url
        ? await axios.get(url)
        : await axios.get(`/fhir/${encodeURIComponent(resourceType)}?${q}`);
      const data = resp.data;
      const entries = data?.entry || [];
      const mapped = entries.map((e: any) => {
        const r = e.resource || {};
        let display = "(no name)";
        if (r.name && r.name.length > 0) {
          const n = r.name[0];
          if (n.text) display = n.text;
          else {
            const given = n.given ? n.given.join(" ") : "";
            display = `${given} ${n.family || ""}`.trim() || display;
          }
        } else if (r.title) display = r.title;
        return {
          id: r.id,
          name: display,
          raw: r,
        };
      });

      const links = data.link || [];
      const next = links.find((l: any) => l.relation === "next");
      const prev = links.find((l: any) => l.relation === "previous" || l.relation === "prev");
      setNextUrl(next ? next.url : null);
      setPrevUrl(prev ? prev.url : null);
      setPatientResults(mapped);
    } catch (e) {
      setError("Failed to search patients");
    } finally {
      setPatientSearching(false);
    }
  };

  const handleFetchPatientById = async () => {
    if (!patientIdFetch || !patientIdFetch.trim()) return;
    try {
      setPatientSearching(true);
      const resp = await axios.get(`/fhir/Patient/${encodeURIComponent(patientIdFetch.trim())}`);
      const r = resp.data;
      // Map resource to same shape as search results
      let display = "(no name)";
      if (r?.name && r.name.length > 0) {
        const n = r.name[0];
        if (n.text) display = n.text;
        else {
          const given = n.given ? n.given.join(" ") : "";
          display = `${given} ${n.family || ""}`.trim() || display;
        }
      } else if (r?.title) display = r.title;

      const mapped = [{ id: r?.id || patientIdFetch.trim(), name: display, raw: r }];
      setPatientResults(mapped);
      setNextUrl(null);
      setPrevUrl(null);
      // clear the id input after successful fetch
      setPatientIdFetch("");
    } catch (e) {
      setError("Failed to fetch patient by id");
      setPatientResults([]);
    } finally {
      setPatientSearching(false);
    }
  };

  const toggleSelectPatient = (patient: { id: string; name: string }) => {
    setSelectedPatients((prev) => {
      if (prev.find((p) => p.id === patient.id)) return prev.filter((p) => p.id !== patient.id);
      return [...prev, { id: patient.id, name: patient.name }];
    });
  };

  const handleSave = async () => {
    try {
      setSubmitting(true);
      const req: BulkGroupRequest = editingId
        ? {
            id,
            name,
            description,
            patientIds: selectedPatients.map((p) => p.id),
          }
        : {
            name,
            description,
            patientIds: selectedPatients.map((p) => p.id),
          };

      let created: BulkGroupResponse | null = null;
      if (editingId) {
        await bulkGroupService.update(editingId, req);
      } else {
        created = await bulkGroupService.create(req);
      }

      closeDialog();
      // If there is an intent telling us to redirect back, do so with a response payload
      if (!editingId && intent && intent.intent_type === 'new_bulk_group' && created) {
        const respPayload = {
          action: 'bulk_group_created',
          clientId: intent.clientId,
          group: created,
        };
        const encResp = encodeIntent(respPayload);
        // navigate back to sourcePath with intent_response
        const target = intent.sourcePath || '/clients';
        navigate(`${target}?intent_response=${encResp}`);
        return;
      }

      loadGroups();
    } catch (e: any) {
      setError(e?.response?.data?.error || "Failed to save bulk group");
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (g: BulkGroupResponse) => {
    // Defensive: if no id, nothing to do
    if (!g?.id) {
      setError("Invalid group id");
      return;
    }

    const ok = confirm(`Delete bulk group ${g.id}?`);
    if (!ok) return;

    try {
      setDeletingId(g.id);
      await bulkGroupService.remove(g.id);
      // reload list
      await loadGroups();
    } catch (e: any) {
      setError(e?.response?.data?.error || "Failed to delete bulk group");
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <Box sx={{ p: 2, width: '100%' }}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", mb: 2 }}>
        <Typography variant="h6">Bulk Groups</Typography>
        <Button startIcon={<AddIcon />} variant="contained" onClick={openCreate}>
          Create Bulk Group
        </Button>
      </Box>

      {error && <Typography color="error">{error}</Typography>}

      <Card>
        <CardContent>
          {loading ? (
            <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
              <CircularProgress />
            </Box>
          ) : groups.length === 0 ? (
            <Typography>No bulk groups found.</Typography>
          ) : (
            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>ID</TableCell>
                    <TableCell>Name</TableCell>
                    <TableCell>Description</TableCell>
                    <TableCell>Members</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {groups.map((g) => (
                    <TableRow
                      key={g.id}
                      hover
                      ref={(el: any) => (rowRefs.current[g.id] = el)}
                      className={highlightId === g.id ? 'flash-highlight' : undefined}
                      style={
                        highlightId === g.id
                          ? ({ ['--flash-count' as any]: highlightCount } as React.CSSProperties)
                          : undefined
                      }
                    >
                      <TableCell>{g.id}</TableCell>
                      <TableCell>{g.name}</TableCell>
                      <TableCell>{g.description}</TableCell>
                      <TableCell>{(g.patientIds || []).length}</TableCell>
                      <TableCell align="right">
                        <Tooltip title="Edit">
                          <IconButton size="small" onClick={() => openEdit(g)}>
                            <EditIcon />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title="Delete">
                          <IconButton
                            size="small"
                            onClick={(e) => { e.stopPropagation(); handleDelete(g); }}
                            disabled={deletingId === g.id}
                          >
                            {deletingId === g.id ? <CircularProgress size={18} color="inherit" /> : <DeleteIcon />}
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

      <Dialog open={dialogOpen} onClose={closeDialog} maxWidth="md" fullWidth>
        <DialogTitle>{editingId ? `Edit Bulk Group: ${editingId}` : "Create Bulk Group"}</DialogTitle>
        <DialogContent>
          <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
            {editingId ? (
              <TextField label="ID" value={id} disabled fullWidth InputProps={{ readOnly: true }} />
            ) : null}
            <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} fullWidth />
            <TextField label="Description" value={description} onChange={(e) => setDescription(e.target.value)} fullWidth multiline rows={2} />

            <Box>
              <Typography variant="subtitle1">Search resources to add</Typography>
              <Box sx={{ display: "flex", gap: 2, alignItems: "center", mt: 1, flexWrap: "wrap" }}>
                <FormControl size="small" sx={{ minWidth: 160 }}>
                  <InputLabel id="resource-type-label">Resource Type</InputLabel>
                  <Select
                    labelId="resource-type-label"
                    label="Resource Type"
                    value={resourceType}
                    onChange={(e) => setResourceType(String(e.target.value))}
                  >
                    <MenuItem value="Patient">Patient</MenuItem>
                    <MenuItem value="RelatedPerson">RelatedPerson</MenuItem>
                    <MenuItem value="Practitioner">Practitioner</MenuItem>
                  </Select>
                </FormControl>

                <TextField size="small" label="Name" value={patientQuery} onChange={(e) => setPatientQuery(e.target.value)} />
                <TextField size="small" label="Family" value={patientFamily} onChange={(e) => setPatientFamily(e.target.value)} />
                <TextField size="small" label="Given" value={patientGiven} onChange={(e) => setPatientGiven(e.target.value)} />
                <TextField size="small" label="Identifier" value={patientIdentifier} onChange={(e) => setPatientIdentifier(e.target.value)} />
                <Button
                  onClick={() => handleSearchPatients()}
                  variant="outlined"
                  disabled={
                    !(
                      patientQuery.trim() ||
                      patientFamily.trim() ||
                      patientGiven.trim() ||
                      patientIdentifier.trim() ||
                      (advancedFilter && advancedFilter.trim())
                    )
                  }
                >
                  Search
                </Button>
                {patientSearching && <CircularProgress size={20} />}
              </Box>

               <Box sx={{ mt: 1 }}>
                <TextField
                  size="small"
                  label="Advanced filter (raw FHIR query, e.g. family=Smith&birthdate=eq1990-01-01)"
                  placeholder="identifier=12345&birthdate=eq1990-01-01"
                  fullWidth
                  value={advancedFilter}
                  onChange={(e) => setAdvancedFilter(e.target.value)}
                  helperText="When provided, this raw query is used instead of individual fields."
                />
              </Box>

              <Box sx={{ mt: 2, mb: 1 }}>
                <Typography variant="subtitle1">Get by Id</Typography>
                <Typography variant="caption" color="text.secondary">Enter a Patient resource id to fetch the single Patient using the <code>/fhir/Patient/{'{id}'}</code> route.</Typography>
                <Box sx={{ display: "flex", gap: 2, alignItems: "center", mt: 1 }}>
                  <TextField size="small" label="Patient ID" value={patientIdFetch} onChange={(e) => setPatientIdFetch(e.target.value)} />
                  <Button onClick={handleFetchPatientById} variant="outlined" disabled={!patientIdFetch.trim()}>Fetch</Button>
                </Box>
              </Box>

             

              <Box sx={{ maxHeight: 240, overflow: "auto", mt: 1 }}>
                {patientResults.map((p) => (
                  <Box key={p.id} sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", p: 1, borderBottom: "1px solid #eee" }}>
                    <Box>
                      <Typography variant="body2">{p.name}</Typography>
                      <Typography variant="caption" color="text.secondary">{p.id}</Typography>
                    </Box>
                    <Button size="small" onClick={() => toggleSelectPatient(p)}>
                      {selectedPatients.find((sp) => sp.id === p.id) ? "Remove" : "Add"}
                    </Button>
                  </Box>
                ))}
              </Box>

              <Box sx={{ display: "flex", justifyContent: "flex-end", gap: 1, mt: 1 }}>
                <Button size="small" disabled={!prevUrl} onClick={() => prevUrl && handleSearchPatients(prevUrl)}>Previous</Button>
                <Button size="small" disabled={!nextUrl} onClick={() => nextUrl && handleSearchPatients(nextUrl)}>Next</Button>
              </Box>

              <Box sx={{ mt: 1 }}>
                <Typography variant="subtitle2">Selected ({selectedPatients.length})</Typography>
                <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap", mt: 1 }}>
                  {selectedPatients.map((p) => (
                    <Chip key={p.id} label={`${p.name} (${p.id})`} onDelete={() => setSelectedPatients((prev) => prev.filter((x) => x.id !== p.id))} />
                  ))}
                </Box>
              </Box>
            </Box>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={closeDialog} disabled={submitting}>Cancel</Button>
          <Button onClick={handleSave} variant="contained" disabled={submitting}>{submitting ? (editingId ? "Saving..." : "Creating...") : (editingId ? "Save" : "Create")}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default BulkGroups;
