const thresholds = (metric: string, value: number) => {
  switch (metric) {
    case "opsPerSec":
      if (value > 10000) {
        return "success";
      } else if (value > 5000) {
        return "warning";
      } else {
        return "error";
      }
    case "diskUsed":
      if (value < 1000000000) {
        return "success";
      } else if (value < 2000000000) {
        return "warning";
      } else {
        return "error";
      }
    case "residentRatio":
      if (value > 90) {
        return "success";
      } else if (value > 20) {
        return "warning";
      } else {
        return "error";
      }
    case "cacheHit":
      if (value > 90) {
        return "success";
      } else if (value > 80) {
        return "warning";
      } else {
        return "error";
      }
    case "cpuUtilization":
      if (value < 50) {
        return "success";
      } else if (value < 80) {
        return "warning";
      } else {
        return "error";
      }
    case "memoryUtilization":
      if (value < 50) {
        return "success";
      } else if (value < 80) {
        return "warning";
      } else {
        return "error";
      }
    case "indexFragmentation":
      if (value < 20) {
        return "success";
      } else if (value < 80) {
        return "warning";
      } else {
        return "error";
      }
    default:
      return "success";
  }
};

export default thresholds;
