import {
  type LinearProgressProps,
  Box,
  LinearProgress,
  Typography,
} from "@mui/material";

const LinearProgressWithLabel = (
  props: LinearProgressProps & { value: number; flag?: string }
) => {
  // Determine the background color based on the flag prop
  const getColor = (flag: string | undefined) => {
    switch (flag) {
      case "success":
        return "#4caf50";
      case "warning":
        return "#ffeb3b";
      case "error":
        return "#f44336";
      default:
        return "#4caf50"; // Default color
    }
  };
  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "center",
        minWidth: 40,
        height: "15px",
        padding: 0,
        margin: 0,
      }}
    >
      <Box sx={{ width: "100%", marginRight: "2px" }}>
        <LinearProgress
          variant="determinate"
          {...props}
          sx={{
            flexGrow: 1,
            "& .MuiLinearProgress-bar": {
              backgroundColor: getColor(props.flag), // Set your preferred color here
            },
            height: 15,
          }}
        />
      </Box>
      <Box sx={{ padding: 0, margin: 0 }}>
        <Typography variant="body2" color="text.secondary">{`${Math.round(
          props.value
        )}%`}</Typography>
      </Box>
    </Box>
  );
};

export default LinearProgressWithLabel;
