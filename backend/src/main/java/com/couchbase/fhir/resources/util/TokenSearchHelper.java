package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import com.couchbase.fhir.search.model.TokenParam;

import java.util.Optional;



public class TokenSearchHelper {

    public static String buildTokenWhereClause(FhirContext fhirContext , String resourceType, String paramName, String tokenValue) {

        TokenParam token = new TokenParam(tokenValue);
        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        RuntimeSearchParam searchParam = def.getSearchParam(paramName);

        if (searchParam == null || searchParam.getParamType() != RestSearchParameterTypeEnum.TOKEN) {
            return null;
        }

        // Example FHIRPath: Patient.identifier
        String path = searchParam.getPath();
        if ("Resource.id".equals(path) || (resourceType + ".id").equals(path)) {
            return "c.id = \"" + token.code + "\"";
        }

        String jsonPath = toCouchbasePath(path, resourceType);

        // Based on FHIR semantics, identifiers and codings are arrays
        String alias = "iden"; // loop variable in ANY expression

        StringBuilder whereClause = new StringBuilder();
        whereClause.append("ANY ").append(alias).append(" IN ").append(jsonPath).append(" SATISFIES ");

        if (token.system != null) {
            whereClause.append(alias).append(".`system` = \"").append(token.system).append("\" AND ");
        }

        whereClause.append(alias).append(".`value` = \"").append(token.code).append("\" END");

        return whereClause.toString();
    }

    private static String toCouchbasePath(String fhirPath, String resourceType) {
        if (fhirPath == null) {
            throw new IllegalArgumentException("FHIRPath is null ");
        }

        if (!fhirPath.startsWith(resourceType + ".") && !fhirPath.startsWith("Resource.")) {
            throw new IllegalArgumentException("Invalid FHIRPath: " + fhirPath);
        }

        // Strip resourceType prefix
        String subPath = fhirPath.substring(resourceType.length() + 1);

        // Convert to JSON field path: identifier.coding -> identifier
        String jsonPath = subPath
                .replace(".coding", "") // optional - Couchbase model may flatten this
                .replace(".value", "")
                .replace(".code", "")
                .replace(".system", "");

        // For now, assume most are arrays
        if (!jsonPath.contains("[")) {
            jsonPath = "c."+jsonPath + "";
        }

        return jsonPath;
    }

}
