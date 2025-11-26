import React from "react";
import { Box, Card, CardContent, Typography, Alert, Chip } from "@mui/material";
import { BsGear } from "react-icons/bs";

/**
 * Client Registration Page
 * Placeholder for future SMART app client registration functionality
 *
 * This page will allow developers to:
 * - Register new SMART on FHIR client applications
 * - Configure redirect URIs, scopes, and grant types
 * - Manage client credentials (client_id, client_secret)
 * - View and revoke registered clients
 */
const ClientRegistration: React.FC = () => {
  return (
    <Box sx={{ p: 2, width: "100%" }}>
      {/* Header */}
      <Box
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          mb: 2,
        }}
      >
        <Typography variant="h6">Client Registration</Typography>
        <Chip
          label="Coming Soon"
          color="primary"
          size="small"
          sx={{ fontWeight: 600 }}
        />
      </Box>

      {/* Info Alert */}
      <Alert severity="info" sx={{ mb: 3 }}>
        <Typography variant="body2" fontWeight="medium">
          SMART on FHIR Client Registration
        </Typography>
        <Typography variant="body2" sx={{ mt: 1 }}>
          This page will allow you to register and manage OAuth 2.0 clients for
          SMART on FHIR applications.
        </Typography>
      </Alert>

      {/* Placeholder Card */}
      <Card>
        <CardContent>
          <Box
            sx={{
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              minHeight: 300,
              gap: 2,
            }}
          >
            <BsGear size={64} style={{ opacity: 0.3 }} />
            <Typography variant="h6" color="text.secondary">
              Client Registration Coming Soon
            </Typography>
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ maxWidth: 500, textAlign: "center" }}
            >
              This feature will enable you to register SMART on FHIR client
              applications, configure OAuth 2.0 settings, and manage client
              credentials for secure API access.
            </Typography>

            {/* Future Features List */}
            <Box
              sx={{ mt: 2, textAlign: "left", width: "100%", maxWidth: 500 }}
            >
              <Typography variant="body2" fontWeight="medium" sx={{ mb: 1 }}>
                Planned Features:
              </Typography>
              <Typography
                variant="body2"
                color="text.secondary"
                component="ul"
                sx={{ pl: 2 }}
              >
                <li>Dynamic client registration (OAuth 2.0 RFC 7591)</li>
                <li>Client credential management (client_id, client_secret)</li>
                <li>Redirect URI configuration and validation</li>
                <li>Scope restrictions and permissions</li>
                <li>
                  Grant type selection (authorization_code, client_credentials,
                  etc.)
                </li>
                <li>Client status management (active, revoked, suspended)</li>
                <li>Audit logs for client operations</li>
              </Typography>
            </Box>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
};

export default ClientRegistration;
