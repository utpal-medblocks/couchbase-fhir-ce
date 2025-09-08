package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.SearchParameter;

import java.util.Collections;
import java.util.List;

public class SearchHelper {
    public RuntimeSearchParam getSearchParam(
                                             RuntimeResourceDefinition resDef,
                                             ValidationSupportChain validationSupportChain,
                                             String searchParamCode) {

        // 1️⃣ Check if the resource definition already has it
        RuntimeSearchParam existing = resDef.getSearchParam(searchParamCode);
        if (existing != null) {
            return existing; // already registered
        }

        // 2️⃣ Fetch all SearchParameters from validationSupportChain
        List<IBaseResource> allSPs = validationSupportChain.fetchAllSearchParameters();
        for (IBaseResource r : allSPs) {
            if (r instanceof SearchParameter sp) {
                // match by code and base resource
                if (sp.getCode().equals(searchParamCode)
                        && !sp.getBase().isEmpty()
                        && sp.getBase().get(0).getValue().equals(resDef.getName())) {

                    // Convert SearchParameter -> RuntimeSearchParam
                    RuntimeSearchParam runtimeParam = new RuntimeSearchParam(
                            null,                        // ID (optional)
                            sp.getUrl(),                 // URI
                            sp.getCode(),                // name/code
                            sp.getDescription(),         // description
                            sp.getExpression(),          // FHIRPath expression
                            RestSearchParameterTypeEnum.valueOf(sp.getType().toCode().toUpperCase()),
                            Collections.emptySet(),      // providesMembershipInCompartments
                            Collections.emptySet(),      // targets
                            RuntimeSearchParam.RuntimeSearchParamStatusEnum.ACTIVE,
                            Collections.singleton(sp.getBase().get(0).getValue())
                    );

                    return runtimeParam;
                }
            }
        }

        // 3️⃣ Not found
        return null;
    }

}
