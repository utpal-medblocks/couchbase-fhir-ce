import React from "react";
import { Editor } from "@monaco-editor/react";

interface EditorProps {
  value: string;
  language?: string;
  theme?: "light" | "dark";
  height?: string;
  width?: string;
}

const EditorComponent: React.FC<EditorProps> = ({
  value,
  language = "json",
  theme = "dark",
  height = "100%",
  width = "100%",
}) => {
  // Format JSON if the language is json and value is valid JSON
  const formatValue = (val: string) => {
    if (language === "json" && val) {
      try {
        return JSON.stringify(JSON.parse(val), null, 2);
      } catch {
        return val; // Return original if parsing fails
      }
    }
    return val;
  };

  const monacoTheme = theme === "dark" ? "vs-dark" : "vs";

  return (
    <Editor
      height={height}
      width={width}
      language={language}
      theme={monacoTheme}
      value={formatValue(value)}
      options={{
        readOnly: true,
        fontFamily: "monospace",
        fontSize: 14,
        selectOnLineNumbers: true,
        roundedSelection: false,
        automaticLayout: true,
        wordWrap: "off",
        minimap: { enabled: false },
        showUnused: false,
        folding: true,
        lineNumbersMinChars: 3,
        scrollBeyondLastLine: false,
        contextmenu: false,
        copyWithSyntaxHighlighting: true,
        renderWhitespace: "none",
        scrollbar: {
          vertical: "visible",
          horizontal: "visible",
          useShadows: false,
          verticalHasArrows: false,
          horizontalHasArrows: false,
        },
      }}
    />
  );
};

export default EditorComponent;
