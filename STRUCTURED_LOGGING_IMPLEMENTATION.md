# Structured Logging Implementation

## Overview

Added structured, Loki-friendly logging at INFO level for all completed HTTP requests. This enables easy performance analysis during load testing without impacting application performance.

## Changes Made

### 1. RequestPerfBag.java

- **Added**: `getStructuredLogLine(method, path)` method
- **Purpose**: Generates structured key-value log format
- **Format**: `reqId=xxx method=GET path="..." duration_ms=123 status=success entries=10 bytes=1066 resource=Patient operation=SEARCH_TYPE`

### 2. BucketAwareValidationInterceptor.java

- **Modified**: `SERVER_OUTGOING_RESPONSE` hook (line ~138-158)

  - Added INFO-level structured log for successful requests
  - Format: `✅ COMPLETED: reqId=xxx method=GET path="..." duration_ms=123 status=success entries=10 bytes=1066`
  - Kept existing DEBUG log for detailed metrics

- **Modified**: `SERVER_HANDLE_EXCEPTION` hook (line ~160-191)
  - Added INFO-level structured log for failed requests
  - Format: `❌ FAILED: reqId=xxx method=GET path="..." duration_ms=123 status=error error=ResourceNotFoundException message="..."`
  - Kept existing DEBUG log for detailed troubleshooting

### 3. SearchService.java

- **Added**: Response metrics tracking in perfBag at all bundle return points
- **Metrics tracked**:
  - `entries`: Number of resources in response bundle (primaries + includes)
  - `response_bytes`: Size of response in bytes
- **Updated methods**:
  - `handleMultipleIncludeSearchFastpath()` - 3 return points
  - `handleMultipleRevIncludeSearchFastpath()` - 2 return points
  - Regular search methods - 2 return points

### 4. logback-spring.xml

- **Modified**: Log pattern for production-ready format
  - Changed timestamp from `HH:mm:ss.SSS` to ISO-8601 UTC: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`
  - Removed logger name (`%logger{36}`) for cleaner output
  - Result: `2025-12-06T22:20:03.768Z INFO COMPLETED: ...`

### 5. Path Extraction

- **Added**: `extractPath()` helper method in BucketAwareValidationInterceptor
- **Purpose**: Strip base URL to show only the FHIR path
  - Before: `http://localhost:8080/fhir/Patient/123`
  - After: `/Patient/123`
- **Benefit**: Cleaner logs, easier pattern matching

## Log Examples

### Successful GET Request (Search)

```
2025-12-06T22:20:03.768Z INFO COMPLETED: reqId=eea45ad5 method=GET path="/Practitioner?_count=50" duration_ms=12 status=success entries=50 bytes=12450 resource=Practitioner operation=SEARCH_TYPE
```

### Successful POST Request

```
2025-12-06T22:20:03.812Z INFO COMPLETED: reqId=f3b2c8a1 method=POST path="/Patient" duration_ms=45 status=success resource=Patient operation=CREATE
```

### Failed Request

```
2025-12-06T22:20:03.845Z ERROR FAILED: reqId=a7d9e4c2 method=GET path="/Patient/nonexistent" duration_ms=8 status=error resource=Patient operation=READ error=ResourceNotFoundException message="Resource not found"
```

## Performance Analysis Usage

### Grep Examples

```bash
# Find slow requests (>100ms)
grep "COMPLETED" fhir-server.log | grep -E "duration_ms=[0-9]{3,}"

# Find requests by method
grep "COMPLETED" fhir-server.log | grep "method=POST"

# Find large responses (>10KB)
grep "COMPLETED" fhir-server.log | grep -E "bytes=[0-9]{5,}"

# Track a specific request through logs
grep "reqId=eea45ad5" fhir-server.log

# Average response time
grep "COMPLETED" fhir-server.log | grep -oP "duration_ms=\K[0-9]+" | awk '{sum+=$1; count++} END {print sum/count}'
```

### Loki LogQL Examples

```logql
# All completed requests with duration > 100ms
{job="fhir-server"} |= "COMPLETED" | logfmt | duration_ms > 100

# POST requests that succeeded
{job="fhir-server"} |= "COMPLETED" | logfmt | method="POST" | status="success"

# Average duration by operation type
sum by (operation) (rate({job="fhir-server"} |= "COMPLETED" | logfmt | unwrap duration_ms [5m]))

# Count of requests by resource type
sum by (resource) (count_over_time({job="fhir-server"} |= "COMPLETED" | logfmt [5m]))

# Track error rate
sum(rate({job="fhir-server"} |= "FAILED" [5m])) / sum(rate({job="fhir-server"} |= "INCOMING REQUEST" [5m]))
```

## Performance Impact

### Overhead Analysis

- **Timing**: Uses existing `System.nanoTime()` calls (no additional overhead)
- **Metrics collection**: Already done by SearchService (no additional overhead)
- **Logging**: Single string format operation at request completion
- **Estimated overhead**: < 0.1ms per request
- **Memory impact**: Negligible (string formatting only)

### When Logging Occurs

- **INFO logs**: Always written (request start + completion)
- **DEBUG logs**: Only when DEBUG level enabled (detailed metrics)
- **No impact during request processing**: All metrics collected passively

## Benefits

1. **Performance Testing**: Easily identify bottlenecks during load tests
2. **Production Monitoring**: Track request patterns and response sizes
3. **Troubleshooting**: Correlate requests using reqId
4. **Loki Integration**: Native support for modern log aggregation
5. **Grep-Friendly**: Easy parsing with standard Unix tools
6. **No Code Changes for Analysis**: Just change log level or grep patterns

## Backward Compatibility

- **Existing DEBUG logs**: Preserved (detailed metrics for troubleshooting)
- **Existing INFO logs**: Enhanced (added structured completion logs)
- **No breaking changes**: All existing functionality maintained
- **Graceful degradation**: Works even if perfBag is missing (fallback mode)

## Configuration

No configuration changes required. The feature works out of the box with:

- `INFO` level: Structured request completion logs
- `DEBUG` level: All of the above + detailed metrics

To disable structured INFO logs (not recommended for performance testing):

```yaml
logging:
  level:
    com.couchbase.fhir.resources.interceptor.BucketAwareValidationInterceptor: WARN
```
