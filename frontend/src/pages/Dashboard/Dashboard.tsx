import { Box, Typography, Card, CardContent } from "@mui/material";
import DashboardCouchbaseServer from "./DashboardCouchbaseServer";
import DashboardFhirServer from "./DashboardFhirServer";
import { useConnectionStore } from "../../store/connectionStore";

export default function Dashboard() {
  //  console.log("🏠 Dashboard: Component rendering");
  const version = "1.0.0";
  const { connection } = useConnectionStore();

  //  console.log("🔗 Dashboard: Connection info loaded:", connection);

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
      <Typography variant="h6" gutterBottom>
        Dashboard
      </Typography>
      <Typography
        variant="subtitle2"
        color="text.secondary"
        sx={{ lineHeight: 1.0 }}
      >
        <em>
          Welcome to Couchbase FHIR CE - Community Edition Version {version}
        </em>
      </Typography>

      <Box
        flex={1}
        display="flex"
        // flexDirection={{ xs: "column", md: "row" }}
        flexDirection="row"
        gap={2}
        mt={1}
        minHeight={0}
        width="100%"
      >
        <Box
          flex={1}
          sx={{
            height: "100%",
            overflow: "hidden",
            border: 1.0,
            borderColor: "divider",
          }}
        >
          <Card
            sx={{ height: "100%", display: "flex", flexDirection: "column" }}
          >
            <CardContent
              sx={{
                p: 0,
                display: "flex",
                flexDirection: "column",
                flex: 1,
                overflow: "hidden",
              }}
            >
              {/* Fixed header */}
              <Box
                sx={{
                  px: 1,
                  py: 1,
                  borderBottom: 1,
                  borderBottomColor: "divider",
                  flexShrink: 0,
                }}
              >
                <Typography
                  variant="subtitle1"
                  align="center"
                  sx={{ lineHeight: 1 }}
                >
                  Couchbase Server Details
                </Typography>
              </Box>
              {/* Scrollable content */}
              <Box
                sx={{
                  flex: 1,
                  overflowY: "auto",
                  px: 1,
                  py: 1,
                }}
              >
                <DashboardCouchbaseServer />
              </Box>
            </CardContent>
          </Card>
        </Box>
        <Box
          flex={1}
          sx={{
            height: "100%",
            overflow: "hidden",
            border: 1.0,
            borderColor: "divider",
          }}
        >
          <Card
            sx={{ height: "100%", display: "flex", flexDirection: "column" }}
          >
            {/* CardContent must fill vertical space */}
            <CardContent
              sx={{
                p: 0,
                display: "flex",
                flexDirection: "column",
                flex: 1,
                overflow: "hidden",
              }}
            >
              {/* Fixed header */}
              <Box
                sx={{
                  px: 1,
                  py: 1,
                  borderBottom: 1,
                  borderBottomColor: "divider",
                  flexShrink: 0,
                }}
              >
                <Typography
                  variant="subtitle1"
                  align="center"
                  sx={{ lineHeight: 1 }}
                >
                  FHIR Server Details
                </Typography>
              </Box>

              {/* Scrollable content */}
              <Box
                sx={{
                  flex: 1,
                  overflowY: "auto",
                  px: 1,
                  py: 1,
                }}
              >
                <DashboardFhirServer />
              </Box>
            </CardContent>
          </Card>
        </Box>
      </Box>
    </Box>
  );
}
