/**
 * Utility functions for managing session storage with type safety
 */

import type { TimeRange } from "../services/bucketMetricsService";

const SESSION_STORAGE_KEYS = {
  TIME_RANGE: "metrics_time_range",
  SELECTED_FTS_INDEX: "fts_selected_index",
  SELECTED_FTS_TAB: "fts_selected_tab",
} as const;

/**
 * Get the stored time range from session storage
 * @param defaultValue - Default value if no stored value exists
 * @returns The stored time range or default
 */
export const getStoredTimeRange = (
  defaultValue: TimeRange = "HOUR"
): TimeRange => {
  try {
    const stored = sessionStorage.getItem(SESSION_STORAGE_KEYS.TIME_RANGE);
    if (stored) {
      // Validate that the stored value is a valid TimeRange
      const validRanges: TimeRange[] = [
        "MINUTE",
        "HOUR",
        "DAY",
        "WEEK",
        "MONTH",
      ];
      if (validRanges.includes(stored as TimeRange)) {
        return stored as TimeRange;
      }
    }
  } catch (error) {
    console.warn("Failed to read time range from session storage:", error);
  }
  return defaultValue;
};

/**
 * Store the time range in session storage
 * @param timeRange - The time range to store
 */
export const storeTimeRange = (timeRange: TimeRange): void => {
  try {
    sessionStorage.setItem(SESSION_STORAGE_KEYS.TIME_RANGE, timeRange);
  } catch (error) {
    console.warn("Failed to store time range in session storage:", error);
  }
};

/**
 * Get the stored selected FTS index from session storage
 * @param defaultValue - Default value if no stored value exists
 * @returns The stored index name or default
 */
export const getStoredFtsIndex = (defaultValue: string = ""): string => {
  try {
    const stored = sessionStorage.getItem(
      SESSION_STORAGE_KEYS.SELECTED_FTS_INDEX
    );
    return stored || defaultValue;
  } catch (error) {
    console.warn("Failed to read FTS index from session storage:", error);
    return defaultValue;
  }
};

/**
 * Store the selected FTS index in session storage
 * @param indexName - The index name to store
 */
export const storeFtsIndex = (indexName: string): void => {
  try {
    sessionStorage.setItem(SESSION_STORAGE_KEYS.SELECTED_FTS_INDEX, indexName);
  } catch (error) {
    console.warn("Failed to store FTS index in session storage:", error);
  }
};

/**
 * Get the stored selected FTS tab from session storage
 * @param defaultValue - Default value if no stored value exists
 * @returns The stored tab index or default
 */
export const getStoredFtsTab = (defaultValue: number = 0): number => {
  try {
    const stored = sessionStorage.getItem(
      SESSION_STORAGE_KEYS.SELECTED_FTS_TAB
    );
    if (stored) {
      const tabIndex = parseInt(stored, 10);
      // Validate tab index (0-2 for JSON, Tree, Metrics)
      if (tabIndex >= 0 && tabIndex <= 2) {
        return tabIndex;
      }
    }
  } catch (error) {
    console.warn("Failed to read FTS tab from session storage:", error);
  }
  return defaultValue;
};

/**
 * Store the selected FTS tab in session storage
 * @param tabIndex - The tab index to store
 */
export const storeFtsTab = (tabIndex: number): void => {
  try {
    sessionStorage.setItem(
      SESSION_STORAGE_KEYS.SELECTED_FTS_TAB,
      tabIndex.toString()
    );
  } catch (error) {
    console.warn("Failed to store FTS tab in session storage:", error);
  }
};
