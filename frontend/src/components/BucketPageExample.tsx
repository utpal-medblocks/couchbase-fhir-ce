// Example usage of the bucketStore with FHIR bucket filtering
// This shows how to use the store in a React component

import React from "react";
import { useBucketStore } from "../store/bucketStore";
import { useConnectionStore } from "../store/connectionStore";

export const BucketPageExample: React.FC = () => {
  const connectionStore = useConnectionStore();
  const bucketStore = useBucketStore();

  // Get the current connection ID (you'd get this from your routing/context)
  const connectionId = connectionStore.connection.name;

  // Get only FHIR buckets for the dropdown
  const fhirBuckets = bucketStore.getFhirBuckets(connectionId);

  // Get current active selections
  const activeBucket = bucketStore.getActiveBucket(connectionId);
  const activeScope = bucketStore.getActiveScope(connectionId);

  // Get collections for the active bucket and scope
  const collections = bucketStore.collections[connectionId] || [];

  const handleBucketChange = (bucketName: string) => {
    bucketStore.setActiveBucket(connectionId, bucketName);
  };

  const handleScopeChange = (scopeName: string) => {
    bucketStore.setActiveScope(connectionId, scopeName);
  };

  return (
    <div>
      <h1>FHIR Buckets</h1>

      {/* Bucket Dropdown - Only shows FHIR buckets */}
      <div>
        <label>Select FHIR Bucket:</label>
        <select
          value={activeBucket?.bucketName || ""}
          onChange={(e) => handleBucketChange(e.target.value)}
        >
          <option value="">Select a bucket...</option>
          {fhirBuckets.map((bucket) => (
            <option key={bucket.bucketName} value={bucket.bucketName}>
              {bucket.bucketName}
            </option>
          ))}
        </select>
      </div>

      {/* Scope Dropdown - Fixed scopes: Admin and Resources */}
      <div>
        <label>Select Scope:</label>
        <select
          value={activeScope || ""}
          onChange={(e) => handleScopeChange(e.target.value)}
        >
          <option value="">Select a scope...</option>
          <option value="Admin">Admin</option>
          <option value="Resources">Resources</option>
        </select>
      </div>

      {/* Collections Table */}
      {activeBucket && activeScope && (
        <div>
          <h2>
            Collections in {activeBucket.bucketName} - {activeScope}
          </h2>
          <table>
            <thead>
              <tr>
                <th>Collection Name</th>
                <th>Items</th>
                <th>Disk Size</th>
                <th>Memory Used</th>
                <th>Operations</th>
                <th>Indexes</th>
              </tr>
            </thead>
            <tbody>
              {collections
                .filter(
                  (col) =>
                    col.bucketName === activeBucket.bucketName &&
                    col.scopeName === activeScope
                )
                .map((collection) => (
                  <tr
                    key={`${collection.bucketName}-${collection.scopeName}-${collection.collectionName}`}
                  >
                    <td>{collection.collectionName}</td>
                    <td>{collection.items.toLocaleString()}</td>
                    <td>{(collection.diskSize / 1024 / 1024).toFixed(2)} MB</td>
                    <td>{(collection.memUsed / 1024 / 1024).toFixed(2)} MB</td>
                    <td>{collection.ops.toLocaleString()}</td>
                    <td>{collection.indexes}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Bucket Details Footer */}
      {activeBucket && (
        <div
          style={{
            marginTop: "20px",
            padding: "10px",
            backgroundColor: "#f5f5f5",
          }}
        >
          <h3>Bucket Details: {activeBucket.bucketName}</h3>
          <p>Type: {activeBucket.bucketType}</p>
          <p>Items: {activeBucket.itemCount.toLocaleString()}</p>
          <p>Operations/sec: {activeBucket.opsPerSec.toLocaleString()}</p>
          <p>RAM: {(activeBucket.ram / 1024 / 1024).toFixed(2)} MB</p>
          <p>
            Disk Used: {(activeBucket.diskUsed / 1024 / 1024).toFixed(2)} MB
          </p>
          <p>Quota Used: {activeBucket.quotaPercentUsed.toFixed(1)}%</p>
        </div>
      )}
    </div>
  );
};
