import {
  blue,
  blueGrey,
  cyan,
  deepOrange,
  deepPurple,
  green,
  indigo,
  lime,
  pink,
  red,
  teal,
  yellow,
} from "@mui/material/colors";
import type { Theme } from "@mui/material/styles";

// Form styles from DataStudio CommonComponents
export const formStyles = {
  my: 1,
  "& .MuiForm-root": {
    margin: "20px",
    padding: "20px",
    border: "1px solid lightgray",
    borderRadius: "5px",
  },
  "& .MuiTextField-root": {
    margin: "10px",
    width: "100%",
    fontSize: "0.875rem",
  },
  "& .MuiButton-root": {
    margin: "10px",
  },
  "& .MuiOutlinedInput-root": {
    fontSize: "0.875rem",
  },
  "& .MuiInputLabel-root": {
    fontSize: "0.875rem",
  },
  "& .MuiFormControlLabel-root .MuiFormControlLabel-label": {
    fontSize: ".875rem",
  },
  "& .MuiFormControlLabel-label": {
    fontSize: ".875rem",
  },
  "& .MuiFormControl-root": {
    paddingLeft: "4px",
    paddingRight: "4px",
  },
  "& .MuiSvgIcon-root": {
    fontSize: 16,
  },
  "& .MuiButtonBase-root-MuiRadio-root": {
    paddingTop: "0px",
    paddingBottom: "0px",
  },
  "& .MuiButtonBase-root-MuiRadio-root.Mui-checked": {
    paddingTop: "0px",
    paddingBottom: "0px",
  },
};

// This is for table styles
export const tableStyles = {
  "& .MuiTable-root": {
    width: "100%",
    borderCollapse: "collapse",
  },
  "& .MuiTableRow-root": {
    "&:hover": {
      backgroundColor: "#f5f5f5",
    },
  },
  "& .MuiTableCell-root": {
    padding: "10px",
    border: "1px solid lightgray",
  },
};
export const tableCellStyle = {
  lineHeight: 1.0,
  padding: 1,
  // fontSize: "1.0rem",
};
export const tableHeaderStyle = (theme: Theme) => ({
  // fontSize: "1.0rem",
  lineHeight: 1.0,
  padding: 1,
  background:
    theme.palette.mode === "light" ? theme.palette.grey[100] : blueGrey[900],
});
export const textInputStyles = {
  "& .MuiOutlinedInput-input": {
    padding: "6px", // add your custom padding here
    minWidth: "150px",
  },
  "& .MuiFormControl-root": {
    margin: 0, // add your custom padding here
    paddingRight: "8px",
  },
  "& .MuiOutlinedInput-root": {
    fontSize: "0.875rem",
    color: "skyblue",
  },
  "& .MuiTextField-root": {
    my: "2px",
  },
};
export const RadioStyle = {
  "& .MuiSvgIcon-root": {
    fontSize: 16,
  },
  "& .MuiButtonBase-root-MuiRadio-root": {
    paddingTop: "0px",
    paddingBottom: "0px",
  },
  "& .MuiButtonBase-root-MuiRadio-root.Mui-checked": {
    paddingTop: "0px",
    paddingBottom: "0px",
  },
};
// This is for Colors
export const BRIGHT_COLORS = [
  blue["500"],
  red["500"],
  green["500"],
  indigo["500"],
  yellow["500"],
  teal["500"],
  deepOrange["500"],
  cyan["500"],
  pink["500"],
  deepPurple["500"],
  lime["500"],
  blueGrey["500"],
];
