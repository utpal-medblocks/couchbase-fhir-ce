import React from "react";
import { SimpleTreeView, TreeItem } from "@mui/x-tree-view";
import { Typography, Box } from "@mui/material";
import { v4 as uuidv4 } from "uuid";
import {
  green,
  amber,
  blue,
  purple,
  grey,
  red,
  cyan,
  lime,
  lightGreen,
  indigo,
} from "@mui/material/colors";

interface FhirTreeViewProps {
  data: any;
  theme?: "light" | "dark";
}

const FhirTreeView: React.FC<FhirTreeViewProps> = ({
  data,
  theme = "dark",
}) => {
  const typeColors: Record<string, string> = {
    string: theme == "dark" ? lightGreen.A200 : green[900],
    number: theme == "dark" ? amber.A200 : amber[900],
    boolean: theme == "dark" ? red.A200 : red[900],
    object: theme == "dark" ? lime.A200 : indigo[900],
    array: theme == "dark" ? cyan.A200 : blue[900],
    null: theme == "dark" ? grey.A200 : grey[900],
  };

  const defaultColor = "#616161";

  const getValueType = (value: any): string => {
    if (value === null) return "null";
    if (Array.isArray(value)) return "array";
    return typeof value;
  };

  const formatValue = (value: any): string => {
    if (value === null) return "null";
    if (typeof value === "string") return `"${value}"`;
    if (typeof value === "boolean") return value.toString();
    if (typeof value === "number") return value.toString();
    return String(value);
  };

  const renderTreeNode = (
    key: string,
    value: any,
    parentKey?: string
  ): React.ReactNode => {
    const nodeId = `${parentKey ? parentKey + "-" : ""}${key}-${uuidv4()}`;
    const valueType = getValueType(value);
    const typeColor = typeColors[valueType] || defaultColor;

    // Handle arrays
    if (Array.isArray(value)) {
      if (value.length === 0) {
        return (
          <TreeItem
            key={nodeId}
            itemId={nodeId}
            label={
              <Box sx={{ display: "flex", alignItems: "center" }}>
                <Typography variant="body2" sx={{ fontWeight: 500, mr: 1 }}>
                  {key}:
                </Typography>
                <Typography
                  variant="body2"
                  sx={{ color: typeColor, fontStyle: "italic" }}
                >
                  [] (empty array)
                </Typography>
              </Box>
            }
          />
        );
      }

      return (
        <TreeItem
          key={nodeId}
          itemId={nodeId}
          label={
            <Box sx={{ display: "flex", alignItems: "center" }}>
              <Typography variant="body2" sx={{ fontWeight: 500, mr: 1 }}>
                {key}:
              </Typography>
              <Typography
                variant="body2"
                sx={{ color: typeColor, fontStyle: "italic" }}
              >
                [{value.length} items]
              </Typography>
            </Box>
          }
        >
          {value.map((item, index) => {
            if (typeof item === "object" && item !== null) {
              return renderTreeNode(`[${index}]`, item, nodeId);
            } else {
              return (
                <TreeItem
                  key={`${nodeId}-${index}`}
                  itemId={`${nodeId}-${index}`}
                  label={
                    <Box sx={{ display: "flex", alignItems: "center" }}>
                      <Typography
                        variant="body2"
                        sx={{ fontWeight: 500, mr: 1 }}
                      >
                        [{index}]:
                      </Typography>
                      <Typography
                        variant="body2"
                        sx={{
                          color: typeColors[getValueType(item)] || defaultColor,
                        }}
                      >
                        {formatValue(item)}
                      </Typography>
                    </Box>
                  }
                />
              );
            }
          })}
        </TreeItem>
      );
    }

    // Handle objects
    if (typeof value === "object" && value !== null) {
      const objectKeys = Object.keys(value);

      if (objectKeys.length === 0) {
        return (
          <TreeItem
            key={nodeId}
            itemId={nodeId}
            label={
              <Box sx={{ display: "flex", alignItems: "center" }}>
                <Typography variant="body2" sx={{ fontWeight: 500, mr: 1 }}>
                  {key}:
                </Typography>
                <Typography
                  variant="body2"
                  sx={{ color: typeColor, fontStyle: "italic" }}
                >
                  {} (empty object)
                </Typography>
              </Box>
            }
          />
        );
      }

      return (
        <TreeItem
          key={nodeId}
          itemId={nodeId}
          label={
            <Box sx={{ display: "flex", alignItems: "center" }}>
              <Typography variant="body2" sx={{ fontWeight: 500, mr: 1 }}>
                {key}:
              </Typography>
              <Typography
                variant="body2"
                sx={{ color: typeColor, fontStyle: "italic" }}
              >
                {`{${objectKeys.length} properties}`}
              </Typography>
            </Box>
          }
        >
          {objectKeys.map((objKey) =>
            renderTreeNode(objKey, value[objKey], nodeId)
          )}
        </TreeItem>
      );
    }

    // Handle primitive values
    return (
      <TreeItem
        key={nodeId}
        itemId={nodeId}
        label={
          <Box sx={{ display: "flex", alignItems: "center" }}>
            <Typography variant="body2" sx={{ fontWeight: 500, mr: 1 }}>
              {key}:
            </Typography>
            <Typography variant="body2" sx={{ color: typeColor }}>
              {formatValue(value)}
            </Typography>
          </Box>
        }
      />
    );
  };

  if (!data || (typeof data === "object" && Object.keys(data).length === 0)) {
    return (
      <Box sx={{ p: 2, textAlign: "center" }}>
        <Typography variant="body2" color="text.secondary">
          No data to display
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ height: "100%", overflow: "auto", p: 1 }}>
      <SimpleTreeView>
        {typeof data === "object" && data !== null
          ? Object.keys(data).map((key) => renderTreeNode(key, data[key]))
          : renderTreeNode("root", data)}
      </SimpleTreeView>
    </Box>
  );
};

export default FhirTreeView;
