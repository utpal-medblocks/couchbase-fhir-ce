package com.couchbase.fhir.search.model;

public class ConceptInfo {
    public boolean isCodableConcept;
    public boolean isArray;
    public boolean isPrimitive;

    public ConceptInfo(boolean isCodableConcept, boolean isArray , boolean isPrimitive) {
        this.isCodableConcept = isCodableConcept;
        this.isArray = isArray;
        this.isPrimitive = isPrimitive;
    }
}
