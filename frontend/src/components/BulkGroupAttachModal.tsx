import React, { useEffect, useState } from "react";
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  Box,
  Radio,
  RadioGroup,
  FormControlLabel,
  Typography,
  Accordion,
  AccordionSummary,
  AccordionDetails,
  List,
  ListItem,
  ListItemText,
  CircularProgress,
  Tooltip,
} from "@mui/material";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import groupService from "../services/groupService";
import oauthClientService from "../services/oauthClientApi";
import { useNavigate } from "react-router-dom";
import { encodeIntent } from "../utils/intent";

interface Props {
  open: boolean;
  onClose: () => void;
  clientId: string;
  onAttached?: (updatedClient: any) => void;
  initialGroups?: any[];
  initialSelectedGroupId?: string;
}

const BulkGroupAttachModal: React.FC<Props> = ({
  open,
  onClose,
  clientId,
  onAttached,
  initialGroups,
  initialSelectedGroupId,
}) => {
  const [groups, setGroups] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [attaching, setAttaching] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    if (open) {
      // Always load groups from API, but merge any initialGroups into the list
      loadGroups(initialGroups);
      // If caller provided a preselected id, set it; otherwise keep null (no auto-select)
      if (initialSelectedGroupId) setSelected(initialSelectedGroupId);
    } else {
      // clear selection when modal closes
      setSelected(null);
    }
  }, [open, initialGroups, initialSelectedGroupId]);

  const loadGroups = async (initial?: any[]) => {
    try {
      setLoading(true);
      const data = (await groupService.getAll()) || [];
      // Merge initial groups (prefer initial group's data when ids match)
      if (initial && initial.length > 0) {
        const map = new Map<string, any>();
        data.forEach((g: any) => map.set(g.id, g));
        initial.forEach((g: any) => {
          const existing = map.get(g.id) || {};
          // Merge shallow fields but ensure nested patient name/map entries are merged
          const merged = { ...existing, ...g };
          merged.patientNames = {
            ...(existing.patientNames || {}),
            ...(g.patientNames || {}),
          };
          // Also merge patientIds ensuring uniqueness and order (existing first)
          const ids = Array.from(
            new Set([...(existing.patientIds || []), ...(g.patientIds || [])])
          );
          merged.patientIds = ids;
          map.set(g.id, merged);
        });
        setGroups(Array.from(map.values()));
      } else {
        setGroups(data);
      }
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const handleAttach = async () => {
    if (!selected) return;
    try {
      setAttaching(true);
      const updated = await oauthClientService.attachBulkGroup(
        clientId,
        selected
      );
      if (onAttached) onAttached(updated);
      onClose();
    } catch (e) {
      console.error("Failed to attach bulk group", e);
    } finally {
      setAttaching(false);
    }
  };

  const handleCreateNew = () => {
    // Build intent: sourcePath and clientId, intent_type
    const intent = {
      intent_type: "new_bulk_group",
      sourcePath: "/clients",
      clientId,
      modalOpen: true,
    };
    const enc = encodeIntent(intent);
    navigate(`/fhir-groups?intent=${enc}`);
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Attach Bulk Group to client: {clientId}</DialogTitle>
      <DialogContent>
        {loading ? (
          <Box sx={{ display: "flex", justifyContent: "center", p: 4 }}>
            <CircularProgress />
          </Box>
        ) : (
          <Box>
            {groups.length === 0 ? (
              <Typography>
                No FHIR groups available. Create one to attach.
              </Typography>
            ) : (
              <RadioGroup
                value={selected || ""}
                onChange={(e) => setSelected(e.target.value)}
              >
                {groups.map((g) => (
                  <Box
                    key={g.id}
                    sx={{ borderBottom: "1px solid #eee", pb: 1, mb: 1 }}
                  >
                    <FormControlLabel
                      value={g.id}
                      control={<Radio />}
                      label={g.name ? `${g.name} (${g.id})` : g.id}
                    />
                    <Accordion disabled={selected !== g.id}>
                      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                        <Typography variant="body2">
                          Members ({(g.patientIds || []).length})
                        </Typography>
                      </AccordionSummary>
                      <AccordionDetails>
                        <Box sx={{ maxHeight: 220, overflow: "auto" }}>
                          <List dense>
                            {(g.patientIds || []).map((pid: string) => (
                              <ListItem key={pid}>
                                <ListItemText
                                  primary={
                                    g.patientNames &&
                                    (g.patientNames[pid] ||
                                      g.patientNames[`Patient/${pid}`])
                                      ? g.patientNames[pid] ||
                                        g.patientNames[`Patient/${pid}`]
                                      : pid
                                  }
                                  secondary={pid}
                                />
                              </ListItem>
                            ))}
                          </List>
                        </Box>
                      </AccordionDetails>
                    </Accordion>
                  </Box>
                ))}
              </RadioGroup>
            )}
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleCreateNew}>Create New Group</Button>
        <Box sx={{ flex: 1 }} />
        <Button onClick={onClose}>Cancel</Button>
        <Tooltip title="Attach selected bulk group to this client">
          <span>
            <Button
              variant="contained"
              onClick={handleAttach}
              disabled={!selected || attaching}
            >
              {attaching ? "Attaching..." : "Attach"}
            </Button>
          </span>
        </Tooltip>
      </DialogActions>
    </Dialog>
  );
};

export default BulkGroupAttachModal;
