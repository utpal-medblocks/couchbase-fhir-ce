package com.couchbase.fhir.resources.repository;

import org.hl7.fhir.instance.model.api.IBaseResource;

import java.util.List;
import java.util.Optional;


public interface FhirResourceDao <T extends IBaseResource>{
    Optional<T> read(String resourceType, String id , String bucketName);
    List<T> readMultiple(String resourceType, List<String> ids, String bucketName);
    Optional<T> create(String resourceType , T resource , String bucketName);

}
