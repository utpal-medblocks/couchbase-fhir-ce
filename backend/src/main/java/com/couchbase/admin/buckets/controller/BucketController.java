package com.couchbase.admin.buckets.controller;

import com.couchbase.admin.buckets.model.Bucket;
import com.couchbase.admin.buckets.service.BucketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/buckets")
@CrossOrigin(origins = "*")
public class BucketController {

    @Autowired
    private BucketService bucketService;

    @GetMapping
    public ResponseEntity<List<Bucket>> getAllBuckets() {
        List<Bucket> buckets = bucketService.getAllBuckets();
        return ResponseEntity.ok(buckets);
    }

    @GetMapping("/{name}")
    public ResponseEntity<Bucket> getBucketByName(@PathVariable String name) {
        Bucket bucket = bucketService.getBucketByName(name);
        if (bucket != null) {
            return ResponseEntity.ok(bucket);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<Bucket> createBucket(@RequestBody Bucket bucket) {
        Bucket createdBucket = bucketService.createBucket(bucket);
        return ResponseEntity.ok(createdBucket);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deleteBucket(@PathVariable String name) {
        boolean deleted = bucketService.deleteBucket(name);
        if (deleted) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
} 