import { Alert, AlertTitle } from "@mui/material";

const Workbench = () => {
  return (
    <Alert severity="warning">
      <AlertTitle>Under construction</AlertTitle>
      This feature is under construction. <strong>Please check later.</strong>
    </Alert>
  );
};

export default Workbench;
