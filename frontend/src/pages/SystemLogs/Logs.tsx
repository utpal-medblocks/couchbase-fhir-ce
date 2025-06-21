import { Typography, Box } from "@mui/material";

const Logs = () => {
  return (
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        height: "100%",
        width: "100%",
      }}
    >
      {/* Top Section - 65% */}
      <Box
        sx={{
          height: "65%",
          width: "100%",
          border: 1,
          borderColor: (theme) =>
            theme.palette.mode === "light"
              ? theme.palette.grey[200]
              : theme.palette.grey[800],
          overflow: "hidden",
        }}
      >
        <Typography>Top section content</Typography>
      </Box>

      {/* Bottom Section - 35% */}
      <Box
        sx={{
          height: "35%",
          width: "100%",
          border: 1,
          borderColor: (theme) =>
            theme.palette.mode === "light"
              ? theme.palette.grey[200]
              : theme.palette.grey[800],
          overflow: "hidden",
        }}
      >
        <Typography>Bottom section content</Typography>
      </Box>
    </Box>
  );
};

export default Logs;
