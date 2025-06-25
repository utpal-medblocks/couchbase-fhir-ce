import React from "react";
import Chip from "@mui/material/Chip";
// Icons from React Icons
import { BsBarChart } from "react-icons/bs"; // Analytics
import { BsArrowRepeat } from "react-icons/bs"; // Eventing
import { BsSearch } from "react-icons/bs"; // Text Search
import { BsInfoCircle } from "react-icons/bs"; // Index
import { BsFileText } from "react-icons/bs"; // Key-Value
import { BsQuestionCircle } from "react-icons/bs"; // Query
import FaceIcon from "@mui/icons-material/Face"; // Default icon for chips
import { Box } from "@mui/material";

interface ChipsArrayProps {
  chipData: string[];
  iconsOnly?: boolean;
}

interface ServiceInfo {
  displayName: string;
  icon: React.ReactElement;
}

// Icon mapping for Couchbase services
const SERVICE_ICONS = {
  cbas: <BsBarChart />, // Analytics
  eventing: <BsArrowRepeat />, // Eventing
  fts: <BsSearch />, // Text Search
  index: <BsInfoCircle />, // Index
  kv: <BsFileText />, // Key-Value
  n1ql: <BsQuestionCircle />, // Query
};

const ChipsArray: React.FC<ChipsArrayProps> = ({
  chipData,
  iconsOnly = false,
}) => {
  // Function to get both display name and icon for each service
  const getServiceInfo = (service: string): ServiceInfo => {
    let displayName: string;

    // Get display name
    if (service === "cbas") {
      displayName = "Analytics";
    } else if (service === "eventing") {
      displayName = "Eventing";
    } else if (service === "fts") {
      displayName = "Text Search";
    } else if (service === "index") {
      displayName = "Index";
    } else if (service === "kv") {
      displayName = "Key-Value";
    } else if (service === "n1ql") {
      displayName = "Query";
    } else {
      displayName = service; // Use the service value as is if no transformation needed
    }

    // Get icon
    const icon = (SERVICE_ICONS[
      service as keyof typeof SERVICE_ICONS
    ] as React.ReactElement) || <FaceIcon />;

    return { displayName, icon };
  };

  // If iconsOnly mode, return just the icons in a compact layout
  if (iconsOnly) {
    return (
      <Box
        component="span"
        sx={{
          display: "inline-flex",
          flexDirection: "row",
          gap: 0.5,
          alignItems: "center",
        }}
      >
        {chipData.map((data, index) => {
          const { displayName, icon } = getServiceInfo(data);
          return (
            <Box
              key={index}
              sx={{
                display: "inline-flex",
                alignItems: "center",
                fontSize: "0.875rem",
                color: "primary.main",
              }}
              title={displayName} // Tooltip on hover
            >
              {icon}
            </Box>
          );
        })}
      </Box>
    );
  }

  return (
    <Box
      component="span"
      sx={{
        display: "inline-flex",
        flexDirection: "row",
        gap: 1,
        flexWrap: "wrap",
        alignItems: "center",
      }}
    >
      {chipData.map((data, index) => {
        const { displayName, icon } = getServiceInfo(data);

        return (
          <Chip
            key={index}
            icon={icon}
            label={displayName}
            size="small"
            variant="outlined"
            color="primary"
          />
        );
      })}
    </Box>
  );
};

export default ChipsArray;
