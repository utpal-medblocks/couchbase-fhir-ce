package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.*;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import com.couchbase.fhir.search.model.TokenParam;


public class TokenSearchHelper {

    public static String buildTokenWhereClause(FhirContext fhirContext , String resourceType, String paramName, String tokenValue ) {

        TokenParam token = new TokenParam(tokenValue);
        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        RuntimeSearchParam searchParam = def.getSearchParam(paramName);
        String path = searchParam.getPath();
        if ("Resource.id".equals(path) || (resourceType + ".id").equals(path)) {
            return "id = \"" + token.code + "\"";
        }else if("Resource.gender".equals(path) || (resourceType + ".gender").equals(path)){
            return "gender = \"" + token.code + "\"";
        }

        boolean codableConcept = isCodableConcept(path , resourceType , def);

        String jsonPath = toCouchbasePath(path, resourceType , codableConcept);
        String alias = "iden";

        StringBuilder whereClause = new StringBuilder();
        whereClause.append("ANY ").append(alias).append(" IN ").append(jsonPath).append(" SATISFIES ");

        if (token.system != null) {
            whereClause.append(alias).append(".`system` = \"").append(token.system).append("\" AND ");
        }
        if(codableConcept){
            whereClause.append(alias).append(".`").append("code").append("` = \"")
                    .append(token.code).append("\" END");
        }else{
            whereClause.append(alias).append(".`value` = \"").append(token.code).append("\" END");
        }

        return whereClause.toString();
    }

    public static boolean isCodableConcept(String path , String resourceType , RuntimeResourceDefinition def){
        boolean isCodableConcept = false;
        try{
            String fhirPath = path.replaceFirst("^" + resourceType + "\\.", "");
            String[] pathParts = fhirPath.split("\\.");
            for (String part : pathParts) {
                if (def != null) {
                    BaseRuntimeChildDefinition child = ((BaseRuntimeElementCompositeDefinition<?>) def).getChildByName(part);
                    if (child != null) {
                        if(child.getChildByName(part).getImplementingClass().getSimpleName().equalsIgnoreCase("CodeableConcept")){
                            isCodableConcept = true;
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println( e.getMessage() );
        }
        return isCodableConcept;
    }


    private static String toCouchbasePath(String fhirPath, String resourceType , boolean codableConcept) {
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

        if (codableConcept) {
            jsonPath += ".coding";
        }
        return jsonPath;
    }

}
