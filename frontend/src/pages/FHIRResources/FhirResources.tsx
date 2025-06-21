import { Box, Typography, Card, CardContent } from "@mui/material";

export default function FhirResources() {
  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        FHIR Resources
      </Typography>

      <Typography variant="body1" color="text.secondary" paragraph>
        Browse and manage FHIR resources stored in your Couchbase database.
      </Typography>

      <Card>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            FHIR Resource Management
          </Typography>
          <Typography variant="body2" color="text.secondary">
            This section will provide functionality to:
          </Typography>
          <Box component="ul" mt={2}>
            <li>Browse FHIR resource types (Patient, Observation, etc.)</li>
            <li>Search and filter resources</li>
            <li>View resource details and relationships</li>
            <li>Validate FHIR compliance</li>
            <li>Import/Export FHIR bundles</li>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
}
