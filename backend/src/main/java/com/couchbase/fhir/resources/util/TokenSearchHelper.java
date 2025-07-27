package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import com.couchbase.fhir.search.model.TokenParam;



public class TokenSearchHelper {

    public static String buildTokenWhereClause(FhirContext fhirContext , String resourceType, String paramName, String tokenValue) {

        TokenParam token = new TokenParam(tokenValue);
        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        RuntimeSearchParam searchParam = def.getSearchParam(paramName);

        if (searchParam == null || searchParam.getParamType() != RestSearchParameterTypeEnum.TOKEN) {
            return null;
        }

        String path = searchParam.getPath();
        if ("Resource.id".equals(path) || (resourceType + ".id").equals(path)) {
            return "id = \"" + token.code + "\"";
        }else if("Resource.gender".equals(path) || (resourceType + ".gender").equals(path)){
            return "gender = \"" + token.code + "\"";
        }

        String jsonPath = toCouchbasePath(path, resourceType);

        String alias = "iden";

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

        String subPath = fhirPath.substring(resourceType.length() + 1);

        String jsonPath = subPath
                .replace(".coding", "")
                .replace(".value", "")
                .replace(".code", "")
                .replace(".system", "");

        return jsonPath;
    }

}
