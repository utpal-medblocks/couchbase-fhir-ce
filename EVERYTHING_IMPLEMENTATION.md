# Patient $everything Operation Implementation

## Overview

The `$everything` operation returns all resources related to a specific patient. This is a powerful FHIR operation that provides a comprehensive view of a patient's clinical data.

## Endpoints

### Instance-Level Operation (Supported)

```
GET /fhir/{bucket}/Patient/{id}/$everything
```

Returns all resources for a specific patient.

### Type-Level Operation (Not Supported)

```
GET /fhir/{bucket}/Patient/$everything
```

This is **NOT** supported. The operation requires a specific patient ID.

## Query Parameters

| Parameter | Type     | Description                                                   | Example                        |
| --------- | -------- | ------------------------------------------------------------- | ------------------------------ |
| `start`   | Date     | Start date filter for clinical resources                      | `?start=2023-01-01`            |
| `end`     | Date     | End date filter for clinical resources                        | `?end=2024-12-31`              |
| `_type`   | String   | Comma-separated list of resource types to include             | `?_type=Observation,Condition` |
| `_since`  | DateTime | Only resources updated after this instant                     | `?_since=2024-01-01T00:00:00Z` |
| `_count`  | Integer  | Maximum number of resources to return (default: 50, max: 200) | `?_count=100`                  |

## Resource Types Included

The operation searches across the following resource types (in order of clinical relevance):

1. **Observation** - Lab results, vital signs, etc.
2. **Condition** - Diagnoses, problems
3. **Encounter** - Visits, admissions
4. **Procedure** - Surgical procedures, treatments
5. **DiagnosticReport** - Lab reports, imaging reports
6. **MedicationRequest** - Prescriptions
7. **Immunization** - Vaccinations
8. **ServiceRequest** - Orders, referrals
9. **DocumentReference** - Clinical documents
10. **General** - Other resources

## Implementation Strategy

### 1. Patient Validation

- **KV Lookup**: Direct key-value retrieval of `Patient/{id}` from the Patient collection
- Returns `404 Not Found` if the patient doesn't exist

### 2. Resource Search

- **FTS Queries**: For each resource type, execute FTS queries to find resources that reference the patient
- **Reference Fields**: Always searches BOTH `patient.reference` OR `subject.reference` for all resource types (simpler and safer)
- **Filters**: Applies date range (`start`, `end`), update time (`_since`), and resource type (`_type`) filters
- **Sorting**: All results sorted by `meta.lastUpdated` DESC (newest first)

### 3. Batch Retrieval

- **Grouping**: Groups all matching keys by resource type
- **KV Operations**: Batch retrieves documents for each resource type (efficient!)
- **Ordering**: Returns resources in clinical relevance order

### 4. Bundle Construction

- **Patient First**: The patient resource is always first in the bundle
- **Search Mode**: Patient has `mode=match`, all others have `mode=include`
- **Count Limiting**: Respects the `_count` parameter (default: 50, max: 200)

## Architecture

```
┌─────────────────────┐
│ FhirCouchbase       │
│ ResourceProvider    │
│ @Operation          │
│ $everything         │
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ EverythingService   │
├─────────────────────┤
│ • Validate patient  │
│ • Determine types   │
│ • Search resources  │
│ • Build bundle      │
└──────────┬──────────┘
           │
           ├──→ CollectionRoutingService (patient collection)
           │
           ├──→ FtsKvSearchService (search each resource type)
           │
           └──→ BatchKvService (batch KV retrieval)
```

## FTS Index Requirements

Each resource type must have FTS indexes with the following fields:

### Required Fields (All Resource Types)

**All FTS indexes must include BOTH reference fields:**

```json
{
  "patient": {
    "properties": {
      "reference": {
        "fields": [{ "index": true, "name": "reference", "type": "text" }]
      }
    }
  },
  "subject": {
    "properties": {
      "reference": {
        "fields": [{ "index": true, "name": "reference", "type": "text" }]
      }
    }
  },
  "meta": {
    "properties": {
      "lastUpdated": {
        "fields": [
          {
            "index": true,
            "docvalues": true,
            "name": "lastUpdated",
            "type": "text"
          }
        ]
      }
    }
  }
}
```

**Why both fields?** The implementation searches `patient.reference OR subject.reference` for ALL resource types. This simplifies the code and ensures compatibility regardless of which field a resource uses.

## Examples

### 1. Get Everything for a Patient

```bash
GET http://localhost:8080/fhir/acme/Patient/1234/$everything
```

**Response:**

```json
{
  "resourceType": "Bundle",
  "type": "searchset",
  "total": 48,
  "entry": [
    {
      "fullUrl": "http://localhost:8080/fhir/acme/Patient/1234",
      "resource": {
        "resourceType": "Patient",
        "id": "1234",
        "name": [{ "family": "Smith", "given": ["John"] }]
      },
      "search": { "mode": "match" }
    },
    {
      "fullUrl": "http://localhost:8080/fhir/acme/Observation/5678",
      "resource": {
        "resourceType": "Observation",
        "id": "5678",
        "subject": { "reference": "Patient/1234" }
      },
      "search": { "mode": "include" }
    }
  ]
}
```

### 2. Filter by Resource Type

```bash
GET http://localhost:8080/fhir/acme/Patient/1234/$everything?_type=Observation,Condition
```

Returns only Observations and Conditions.

### 3. Filter by Date Range

```bash
GET http://localhost:8080/fhir/acme/Patient/1234/$everything?start=2024-01-01&end=2024-12-31
```

Returns only resources with clinical dates in 2024.

### 4. Limit Results

```bash
GET http://localhost:8080/fhir/acme/Patient/1234/$everything?_count=10
```

Returns the patient + up to 9 related resources (10 total).

### 5. Get Recent Updates

```bash
GET http://localhost:8080/fhir/acme/Patient/1234/$everything?_since=2024-10-01T00:00:00Z
```

Returns only resources updated since October 1, 2024.

## Performance Considerations

### Efficient Design

1. **KV for Patient**: Single KV lookup (fastest)
2. **FTS for Search**: Leverages existing indexes
3. **Batch KV**: Groups keys by type for efficient retrieval
4. **Count Limiting**: Prevents massive payloads

### Expected Performance

- **Patient Validation**: < 10ms (KV)
- **FTS Searches**: ~20-50ms per resource type (10 types = ~200-500ms)
- **Batch Retrieval**: ~20-100ms (depends on count)
- **Total**: ~250-600ms for typical requests

### Optimization Tips

1. Use `_type` parameter to search only needed resource types
2. Use `_count` parameter to limit results
3. Use `_since` parameter for incremental updates
4. Ensure FTS indexes include all reference fields

## Comparison with \_include/\_revinclude

| Feature            | $everything                   | \_revinclude                   |
| ------------------ | ----------------------------- | ------------------------------ |
| **Purpose**        | Get ALL patient data          | Get specific related resources |
| **Resource Types** | 10+ types automatically       | 1 type per parameter           |
| **Query**          | Single operation              | Multiple includes needed       |
| **Use Case**       | Complete patient view         | Specific relationships         |
| **Performance**    | Higher cost (10+ FTS queries) | Lower cost (1 FTS query)       |

**When to use $everything:**

- Patient portals (complete record)
- Data export
- Clinical summaries
- Complete patient context

**When to use \_revinclude:**

- Specific queries (e.g., "patients with their observations")
- Better performance
- Targeted data retrieval

## Future Enhancements

### Potential Improvements

1. **Pagination**: Full pagination support with continuation tokens
2. **Date Field Optimization**: Per-resource-type date field mapping
3. **Caching**: Cache recent $everything results
4. **Async Processing**: Background job for large datasets
5. **Compartment Definition**: Use FHIR Compartment resources to define relationships

### Advanced Filters

- `_include`: Also include referenced resources (e.g., practitioners, organizations)
- `_filter`: Advanced FHIRPath-based filtering
- `_sort`: Sort results by date or other criteria

## Error Handling

### Patient Not Found (404)

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "not-found",
      "diagnostics": "Patient/1234 not found"
    }
  ]
}
```

### Invalid Resource Type (200, but filtered out)

```bash
GET /Patient/1234/$everything?_type=InvalidType,Observation
```

Returns only Observations (invalid types are ignored).

### Operation on Non-Patient Resource (400)

```bash
GET /Observation/5678/$everything
```

```json
{
  "resourceType": "OperationOutcome",
  "issue": [
    {
      "severity": "error",
      "code": "invalid",
      "diagnostics": "$everything operation is only supported for Patient resources"
    }
  ]
}
```

## Testing

### Test Scenarios

1. ✅ **Patient Exists**: Returns patient + related resources
2. ✅ **Patient Not Found**: Returns 404
3. ✅ **No Related Resources**: Returns just the patient
4. ✅ **Filter by Type**: Returns only specified resource types
5. ✅ **Date Range**: Returns only resources within date range
6. ✅ **Since Filter**: Returns only recently updated resources
7. ✅ **Count Limit**: Respects \_count parameter
8. ✅ **Non-Patient Resource**: Returns 400 error

### Sample Test Data

**Patient:**

```json
{
  "resourceType": "Patient",
  "id": "1234",
  "name": [{ "family": "Smith", "given": ["John"] }]
}
```

**Observation:**

```json
{
  "resourceType": "Observation",
  "id": "obs-1",
  "subject": { "reference": "Patient/1234" },
  "effectiveDateTime": "2024-10-01T10:00:00Z"
}
```

**Condition:**

```json
{
  "resourceType": "Condition",
  "id": "cond-1",
  "patient": { "reference": "Patient/1234" },
  "recordedDate": "2024-09-15"
}
```

## Logging

### Key Log Patterns

**Success:**

```
🌍 $everything for Patient/1234 (bucket: acme, types: null, count: 50)
🌍 Searching 10 resource types for Patient/1234
🌍 FTS search for Observation (sorted by lastUpdated DESC): 2 queries
🌍 Found 5 Observation resources for Patient/1234
🌍 FTS search for Condition (sorted by lastUpdated DESC): 2 queries
🌍 Found 2 Condition resources for Patient/1234
...
✅ $everything returning 48 total resources (1 Patient + 47 related)
```

**Patient Not Found:**

```
❌ Failed to retrieve Patient/1234: Patient/1234 not found
```

**Performance:**

```
🌍 FTS search for Observation (sorted by lastUpdated DESC): 2 queries
🌍 Retrieved 5/5 Observation resources
...
🌍 Total keys found across all types: 47
```

## Key Design Decisions

### 1. FTS Over N1QL

- **Why**: FTS is optimized for reference field searches
- **Benefit**: 10-50x faster than N1QL for this use case

### 2. Batch KV Over Individual Lookups

- **Why**: Group keys by type for efficient batch retrieval
- **Benefit**: 5-10x faster than individual lookups

### 3. Count-Based Pagination (Not Token-Based)

- **Why**: Simplicity for initial implementation
- **Trade-off**: Less flexible than token-based pagination
- **Future**: Add token-based pagination for large datasets

### 4. In-Memory Aggregation

- **Why**: Results are limited by \_count (max 200)
- **Benefit**: Simple, fast
- **Trade-off**: Not suitable for unrestricted queries

### 5. Sorting by meta.lastUpdated DESC

- **Why**: Show most recent resources first (clinical relevance)
- **Benefit**: Users see latest data immediately
- **Implementation**: Applied to all resource type searches

## Lessons Learned from History Implementation

1. **Let HAPI Handle Bundles**: Initially tried manual bundle construction, but HAPI's built-in mechanisms are more reliable (for `@History`). For `$everything`, we construct the bundle manually for better control.

2. **Resource ID Validation**: Ensure all resources have proper `IdType` set before adding to bundle.

3. **FTS Index Coverage**: Verify all reference fields are indexed in FTS.

4. **Batch Operations**: Always group KV operations by resource type for efficiency.

5. **Uniform Reference Search**: Always search both `patient.reference` AND `subject.reference` for all resource types - simpler than maintaining per-type mappings.

6. **Default Sorting**: Always sort by `meta.lastUpdated DESC` to show newest resources first.

7. **Logging Strategy**: Use emojis (🌍, ✅, ❌) for visual grepping in logs.

## Conclusion

The `$everything` operation provides a powerful way to retrieve a complete patient record in a single request. The implementation leverages:

- **Couchbase KV** for fast patient validation
- **Couchbase FTS** for efficient reference searches across multiple resource types
- **Batch operations** for optimal performance
- **FHIR conventions** for bundle structure and search modes

This implementation balances performance, simplicity, and FHIR compliance to deliver a robust patient data retrieval capability.
