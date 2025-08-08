import React from "react";
import { Box, Typography } from "@mui/material";

// Types
interface FieldData {
  name?: string;
  type?: string;
  index?: boolean;
  docvalues?: boolean;
  analyzer?: string;
  dynamic?: boolean;
  enabled?: boolean;
  fields?: FieldData[];
  properties?: Record<string, FieldData>;
}

interface TypeData {
  dynamic?: boolean;
  enabled?: boolean;
  properties?: Record<string, FieldData>;
}

interface MappingConfig {
  default_analyzer?: string;
  default_datetime_parser?: string;
  default_field?: string;
  docvalues_dynamic?: boolean;
  index_dynamic?: boolean;
  store_dynamic?: boolean;
  types?: Record<string, TypeData>;
  default_mapping?: {
    enabled?: boolean;
    dynamic?: boolean;
  };
}

interface FtsIndexData {
  name?: string;
  type?: string;
  sourceName?: string;
  sourceType?: string;
  uuid?: string;
  params?: {
    mapping?: MappingConfig;
  };
}

interface FTSIndexTreeDisplayProps {
  ftsIndexData: FtsIndexData | null;
}

const FTSIndexTreeDisplay: React.FC<FTSIndexTreeDisplayProps> = ({
  ftsIndexData,
}) => {
  const getTypeColor = (type: string) => {
    switch (type) {
      case "text":
        return "primary.main"; // blue
      case "boolean":
        return "success.main"; // green
      case "datetime":
        return "warning.main"; // orange
      default:
        return "secondary.main"; // purple
    }
  };

  const renderField = (
    fieldName: string,
    fieldData: FieldData,
    depth: number = 0
  ): React.ReactElement[] => {
    const elements: React.ReactElement[] = [];
    const indent = "  ".repeat(depth); // Use spaces for indentation

    const hasFields = fieldData.fields && Array.isArray(fieldData.fields);
    const hasProperties =
      fieldData.properties && typeof fieldData.properties === "object";
    const isDynamic = fieldData.dynamic;

    if (hasFields) {
      // Simple field with field definitions - render each field
      fieldData.fields!.forEach((field, index) => {
        const extras: string[] = [];
        if (field.docvalues) extras.push("docvalues");
        if (
          field.analyzer &&
          field.analyzer !== "keyword" &&
          field.analyzer !== "standard"
        ) {
          extras.push(`analyzer: ${field.analyzer}`);
        }
        if (field.name && field.name !== fieldName) {
          extras.push(`searchable as ${field.name}`);
        }

        const extraText = extras.length > 0 ? ` | ${extras.join(" | ")}` : "";

        elements.push(
          <Typography
            variant="body1"
            key={`${fieldName}-${index}`}
            component="div"
            sx={{
              //   fontFamily: "Monaco, Consolas, 'Courier New', monospace",
              //   fontSize: "14px",
              lineHeight: 1.6,
              marginLeft: `${depth * 20}px`,
            }}
          >
            <Box component="span" sx={{ fontWeight: "bold" }}>
              {indent}
              {fieldName}
            </Box>
            :{" "}
            <Box
              component="span"
              sx={{ color: getTypeColor(field.type || ""), fontWeight: "bold" }}
            >
              {field.type}
            </Box>
            {extraText && (
              <Box component="span" sx={{ color: "text.secondary" }}>
                {extraText}
              </Box>
            )}
          </Typography>
        );
      });
    } else if (hasProperties) {
      // Object with properties - show as {} object
      elements.push(
        <Typography
          variant="body1"
          key={fieldName}
          component="div"
          sx={{
            // fontFamily: "Monaco, Consolas, 'Courier New', monospace",
            // fontSize: "14px",
            lineHeight: 1.6,
            marginLeft: `${depth * 20}px`,
            fontWeight: "bold",
          }}
        >
          {indent}
          {"{}"} {fieldName}
          {isDynamic && (
            <Box
              component="span"
              sx={{
                color: "warning.main",
                fontWeight: "normal",
              }}
            >
              : dynamic
            </Box>
          )}
        </Typography>
      );

      // Recursively render nested properties
      Object.entries(fieldData.properties!).forEach(([propName, propData]) => {
        elements.push(...renderField(propName, propData, depth + 1));
      });
    } else if (isDynamic) {
      // Dynamic object without specific fields
      elements.push(
        <Typography
          variant="body1"
          key={fieldName}
          component="div"
          sx={{
            //   fontFamily: "Monaco, Consolas, 'Courier New', monospace",
            //   fontSize: "14px",
            lineHeight: 1.6,
            marginLeft: `${depth * 20}px`,
            fontWeight: "bold",
          }}
        >
          {indent}
          {"{}"} {fieldName}:{" "}
          <Box
            component="span"
            sx={{
              color: "error.main",
              fontWeight: "normal",
              fontStyle: "italic",
            }}
          >
            dynamic
          </Box>
        </Typography>
      );
    }

    return elements;
  };

  const renderResourceType = (
    typeName: string,
    typeData: TypeData
  ): React.ReactElement[] => {
    const elements: React.ReactElement[] = [];

    // Add the resource type header
    let statusText = "";
    if (typeData.dynamic) {
      statusText = " | dynamic";
    } else if (typeData.enabled !== undefined) {
      statusText = typeData.enabled
        ? " | only index specified fields"
        : " | disabled";
    }

    elements.push(
      <Typography
        variant="h6"
        key={typeName}
        component="div"
        sx={{
          //   fontFamily: "Monaco, Consolas, 'Courier New', monospace",
          //   fontSize: "16px",
          lineHeight: 2,
          fontWeight: "bold",
          color: "text.primary",
          //marginBottom: 1,
        }}
      >
        {typeName}
        {statusText && (
          <Box component="span">
            <Typography
              variant="body1"
              component="span"
              sx={{
                color: "text.secondary",
                fontWeight: "normal",
                fontStyle: "italic",
              }}
            >
              {statusText}
            </Typography>
          </Box>
        )}
      </Typography>
    );

    // Add properties
    if (typeData.properties) {
      Object.entries(typeData.properties).forEach(([fieldName, fieldData]) => {
        elements.push(...renderField(fieldName, fieldData, 1));
      });
    }

    return elements;
  };

  if (!ftsIndexData) {
    return (
      <Box sx={{ p: 3 }}>
        <Typography color="error">No FTS index data provided</Typography>
      </Box>
    );
  }

  // Generate all elements
  const allElements: React.ReactElement[] = [];

  // Add resource types
  if (ftsIndexData.params?.mapping?.types) {
    Object.entries(ftsIndexData.params.mapping.types).forEach(
      ([typeName, typeData]) => {
        allElements.push(...renderResourceType(typeName, typeData));
      }
    );
  }

  // Add default mapping if it exists
  if (ftsIndexData.params?.mapping?.default_mapping) {
    const statusText = ftsIndexData.params.mapping.default_mapping.enabled
      ? "enabled"
      : "disabled";

    allElements.push(
      <Typography
        variant="body1"
        key="default-mapping"
        component="div"
        sx={{
          //   fontFamily: "Monaco, Consolas, 'Courier New', monospace",
          //   fontSize: "14px",
          lineHeight: 1.6,
          fontWeight: "bold",
          //marginTop: 2,
        }}
      >
        default:{" "}
        <Box component="span">
          <Typography
            variant="body1"
            component="span"
            sx={{ color: "text.secondary", fontWeight: "normal" }}
          >
            {statusText}
          </Typography>
        </Box>
      </Typography>
    );
  }

  // Configuration section
  const configItems: string[] = [];
  if (ftsIndexData.params?.mapping) {
    const mapping = ftsIndexData.params.mapping;
    if (mapping.default_analyzer) {
      configItems.push(`default_analyzer: ${mapping.default_analyzer}`);
    }
    if (mapping.default_datetime_parser) {
      configItems.push(
        `default_datetime_parser: ${mapping.default_datetime_parser}`
      );
    }
    if (mapping.docvalues_dynamic !== undefined) {
      configItems.push(`docvalues_dynamic: ${mapping.docvalues_dynamic}`);
    }
    if (mapping.index_dynamic !== undefined) {
      configItems.push(`index_dynamic: ${mapping.index_dynamic}`);
    }
    if (mapping.store_dynamic !== undefined) {
      configItems.push(`store_dynamic: ${mapping.store_dynamic}`);
    }
  }

  return (
    <Box sx={{ p: 0 }}>
      {/* <Typography variant="h5" sx={{ mb: 2, color: "primary.main" }}>
        {ftsIndexData.name}
      </Typography>
      <Divider sx={{ mb: 3 }} /> */}

      {/* Field Structure */}
      <Box sx={{ mb: 1 }}>{allElements}</Box>

      {/* Configuration */}
      {configItems.length > 0 && (
        <Box>
          <Typography variant="h6" sx={{ mb: 1 }}>
            Configuration
          </Typography>
          {configItems.map((item, index) => {
            const [key, value] = item.split(":").map((s) => s.trim());
            return (
              <Typography
                variant="body1"
                key={index}
                component="div"
                sx={{ lineHeight: 1.6 }}
              >
                <Box component="span" sx={{ fontWeight: "bold" }}>
                  {key}
                </Box>
                :{" "}
                <Box
                  component="span"
                  sx={{ color: "text.secondary", fontStyle: "italic" }}
                >
                  {value}
                </Box>
              </Typography>
            );
          })}
        </Box>
      )}
    </Box>
  );
};

export default FTSIndexTreeDisplay;
