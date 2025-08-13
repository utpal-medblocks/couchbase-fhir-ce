# FTS Index Feature Implementation

## Overview

This implementation provides a complete backend and frontend solution for displaying Couchbase Full-Text Search (FTS) index metrics and definitions in the FHIR project.

## Backend Implementation

### Models Created

1. **FtsIndex.java** - Represents FTS index definitions
   - Contains index metadata like name, UUID, type, params, source information
2. **FtsIndexStats.java** - Represents FTS index statistics
   - Contains performance metrics like docs indexed, query latency, disk usage
3. **FtsIndexDetails.java** - Combined view of index definition and stats
   - Provides all information needed for the UI in a single object

### Service Layer

**FtsIndexService.java** - Main service handling FTS operations

- `getFtsIndexDetails()` - Gets combined index info and stats
- `getFtsIndexDefinitions()` - Gets index definitions only
- `getFtsIndexStats()` - Gets index statistics only

Key features:

- Automatically handles SSL/non-SSL connections (port 8094/18094)
- Supports Capella and self-managed Couchbase
- Uses REST API endpoints as specified:
  - Index definitions: `GET /api/bucket/{bucket}/scope/{scope}/index`
  - Statistics: `GET /api/nsstats`

### Controller Layer

**FtsIndexController.java** - REST endpoints for frontend

- `GET /api/fts/indexes` - Get complete index details
- `GET /api/fts/definitions` - Get index definitions only
- `GET /api/fts/stats` - Get index statistics only

## Frontend Implementation

### Store (Zustand)

**ftsIndexStore.ts** - State management for FTS indexes

- Manages loading, error states, and data caching
- Auto-polling every 30 seconds (configurable)
- Proper cleanup and error handling

### Service Layer

**ftsIndexService.ts** - API client for FTS endpoints

- Provides typed methods for all backend endpoints
- Proper error handling and response typing

### UI Component

**FtsIndexTable.tsx** - React component for displaying FTS data

- Two tabs: Overview (metrics table) and Definitions (JSON view)
- Displays all requested metrics:
  - Index Name
  - Status
  - Docs Indexed
  - Last Time Used
  - Query Latency
  - Query Rate
  - Total Queries
  - Disk Size
- Responsive design with Tailwind CSS
- Loading states and error handling

## Usage Example

```typescript
// In a React component
import { useFtsIndexStore } from "../store/ftsIndexStore";
import FtsIndexTable from "../components/FtsIndexTable";

const MyComponent = () => {
  return (
    <FtsIndexTable
      connectionName="my-connection"
      bucketName="us-core"
      scopeName="Resources"
    />
  );
};
```

## API Endpoints

### Get FTS Index Details

```
GET /api/fts/indexes?connectionName=conn&bucketName=bucket&scopeName=scope
```

### Get FTS Index Definitions Only

```
GET /api/fts/definitions?connectionName=conn&bucketName=bucket&scopeName=scope
```

### Get FTS Index Statistics Only

```
GET /api/fts/stats?connectionName=conn
```

## Features Implemented

✅ SSL/TLS support (automatic port detection)  
✅ Capella compatibility  
✅ Error handling and retry logic  
✅ Auto-polling with cleanup  
✅ Responsive UI with loading states  
✅ Two-tab view (metrics + definitions)  
✅ All requested metrics displayed  
✅ Type-safe TypeScript implementation  
✅ Follows existing project patterns

## Next Steps

1. **Frontend Integration** - Add the FtsIndexTable component to your main application routing/navigation
2. **Styling Adjustments** - Customize the component styling to match your existing design system
3. **Additional Features** - Consider adding:
   - Index creation/management UI
   - Real-time query execution
   - Index performance charts
   - Export functionality

## File Structure

```
backend/src/main/java/com/couchbase/admin/fts/
├── controller/
│   └── FtsIndexController.java
├── model/
│   ├── FtsIndex.java
│   ├── FtsIndexDetails.java
│   └── FtsIndexStats.java
└── service/
    └── FtsIndexService.java

frontend/src/
├── components/
│   └── FtsIndexTable.tsx
├── services/
│   └── ftsIndexService.ts
└── store/
    └── ftsIndexStore.ts
```

This implementation provides a solid foundation for FTS index management and can be extended with additional features as needed.
