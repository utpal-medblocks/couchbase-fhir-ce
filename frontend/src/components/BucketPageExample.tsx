// Example usage of the bucketStore in single-tenant mode
// This shows how to use the simplified store in a React component

import React from "react";
import { useBucketStore } from "../store/bucketStore";

export const BucketPageExample: React.FC = () => {
  const bucketStore = useBucketStore();

  // Single-tenant mode: only one bucket named "fhir"
  const bucket = bucketStore.bucket;
  const collections = bucketStore.collections;

  return (
    <div>
      <h1>FHIR System</h1>

      {/* Bucket Info - Fixed to "fhir" bucket */}
      <div>
        <label>Bucket:</label>
        <span> {bucket?.bucketName || "Loading..."}</span>
      </div>

      {/* Scope - Fixed to Resources */}
      <div>
        <label>Scope:</label>
        <span> Resources</span>
      </div>

      {/* Collections Table */}
      {bucket && (
        <div>
          <h2>Collections in {bucket.bucketName} - Resources</h2>
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
                .filter((col) => col.scopeName === "Resources")
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
      {bucket && (
        <div
          style={{
            marginTop: "20px",
            padding: "10px",
            backgroundColor: "#f5f5f5",
          }}
        >
          <h3>Bucket Details: {bucket.bucketName}</h3>
          <p>Type: {bucket.bucketType}</p>
          <p>Items: {bucket.itemCount.toLocaleString()}</p>
          <p>Operations/sec: {bucket.opsPerSec.toLocaleString()}</p>
          <p>RAM: {(bucket.ram / 1024 / 1024).toFixed(2)} MB</p>
          <p>Disk Used: {(bucket.diskUsed / 1024 / 1024).toFixed(2)} MB</p>
          <p>Quota Used: {bucket.quotaPercentUsed.toFixed(1)}%</p>
        </div>
      )}
    </div>
  );
};
