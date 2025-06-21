import React from "react";
import TextField from "@mui/material/TextField";
export interface CustomTextFieldProps {
  label: string;
  helperText: string;
  placeholder?: string;
  value?: string;
  onChange?: React.ChangeEventHandler<HTMLInputElement | HTMLTextAreaElement>;
}

export const CustomTextField: React.FC<CustomTextFieldProps> = ({
  label,
  helperText,
  placeholder,
  value,
  onChange,
}) => (
  <div>
    <TextField
      label={label}
      helperText={helperText}
      placeholder={placeholder}
      value={value}
      onChange={onChange}
      fullWidth
      variant="outlined"
      size="small"
      sx={{
        my: 1,
        "& .MuiInputBase-root": {
          fontSize: "0.875rem",
        },
      }}
    />
  </div>
);
