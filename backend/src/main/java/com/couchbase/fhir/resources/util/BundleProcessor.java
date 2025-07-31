package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;


import java.util.*;

public class BundleProcessor {
    private final Map<String, String> fullUrlToAssignedId = new HashMap<>();
    private static final FhirContext fhirContext = FhirContext.forR4();

    public void process(Bundle bundle) {
        assignRealIds(bundle);
        rewriteReferences(bundle);
        clear();
    }

    private void assignRealIds(Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            String fullUrl = entry.getFullUrl(); // might be urn:uuid:xyz

            boolean isSynthetic = fullUrl != null && fullUrl.startsWith("urn:uuid:");

            if (isSynthetic || resource.getIdElement().isEmpty() || resource.getIdElement().getIdPart().startsWith("urn:uuid:")) {
                // Generate a new ID
                String resourceType = resource.getResourceType().name();
                String newId = UUID.randomUUID().toString();

                // Set the new ID
                resource.setId(newId);

                // Save mapping from urn:uuid to real id (e.g., Patient/abc-123)
                if (isSynthetic) {
                    fullUrlToAssignedId.put(fullUrl, resourceType + "/" + newId);
                }
            }
        }
    }

    private void rewriteReferences(Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            System.out.println(" resource processing -- " + resource.getClass().getSimpleName());
            List<IBaseReference> refs=  getAllPopulatedChildElementsOfType(resource, IBaseReference.class);
            for (IBaseReference ref : refs) {
                if (ref instanceof Reference refStr) {
                    System.out.println("Reference: " + refStr.getReference());
                    if (fullUrlToAssignedId.containsKey(refStr.getReference())) {
                        System.out.println(refStr.getReference() + " setting to "+fullUrlToAssignedId.get(refStr.getReference()));
                        ref.setReference(fullUrlToAssignedId.get(refStr.getReference()));
                    }
                }

            }
        }
    }

    public String getAssignedReference(String fullUrl) {
        return fullUrlToAssignedId.get(fullUrl);
    }

    public void clear() {
        fullUrlToAssignedId.clear();
    }



    public static <T extends IBase> List<T> getAllPopulatedChildElementsOfType(IBaseResource resource, Class<T> type) {
        List<T> result = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        findElements(resource, type, result , visited);
        return result;
    }

    private static <T extends IBase> void findElements(IBase element, Class<T> type, List<T> result , Set<Integer> visited) {
        if (element == null) return;

        int identity = System.identityHashCode(element);
        if (visited.contains(identity)) return; // prevent infinite recursion
        visited.add(identity);

        if (type.isAssignableFrom(element.getClass())) {
            result.add(type.cast(element));
        }

        List<IBase> children = new ArrayList<>();

        if (element instanceof IBaseResource) {
            children = fhirContext.newTerser().getAllPopulatedChildElementsOfType((IBaseResource) element, IBase.class);
        }

        for (IBase child : children) {
            findElements(child, type, result , visited);
        }
    }
}
