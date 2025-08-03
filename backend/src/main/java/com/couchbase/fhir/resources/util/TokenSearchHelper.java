package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.*;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import com.couchbase.fhir.search.model.ConceptInfo;
import com.couchbase.fhir.search.model.TokenParam;


public class TokenSearchHelper {

    public static String buildTokenWhereClause(FhirContext fhirContext , String resourceType, String paramName, String tokenValue ) {

        TokenParam token = new TokenParam(tokenValue);
        RuntimeResourceDefinition def = fhirContext.getResourceDefinition(resourceType);
        RuntimeSearchParam searchParam = def.getSearchParam(paramName);
        String path = searchParam.getPath();
       /* if ("Resource.id".equals(path) || (resourceType + ".id").equals(path)) {
            return "id = \"" + token.code + "\"";
        }else if("Resource.gender".equals(path) || (resourceType + ".gender").equals(path)){
            return "gender = \"" + token.code + "\"";
        }*/

        ConceptInfo conceptInfo = getConceptInfo(path , resourceType , def);

        String jsonPath = toCouchbasePath(path, resourceType , conceptInfo.isCodableConcept , conceptInfo.isArray , conceptInfo.isPrimitive);

        if(conceptInfo.isPrimitive){
            return jsonPath + " = \"" + token.code + "\" ";
        }

        StringBuilder whereClause = new StringBuilder();
        String alias = "iden";
        if(conceptInfo.isCodableConcept && conceptInfo.isArray){

            whereClause.append("ANY cat IN ").append(jsonPath)
                    .append(" SATISFIES ANY ").append(alias).append(" IN cat.coding SATISFIES ");
            if (token.system != null) {
                whereClause.append(alias).append(".`system` = \"").append(token.system).append("\" AND ");
            }
            whereClause.append(alias).append(".`code` = \"").append(token.code).append("\" END END");

        }else if(conceptInfo.isCodableConcept){
            whereClause.append("ANY ").append(alias).append(" IN ").append(jsonPath).append(" SATISFIES ");

            if (token.system != null) {
                whereClause.append(alias).append(".`system` = \"").append(token.system).append("\" AND ");
            }

            whereClause.append(alias).append(".`").append("code").append("` = \"")
                    .append(token.code).append("\" END");
        }
        else{
            whereClause.append("ANY ").append(" c ").append(" IN ").append(jsonPath).append(" SATISFIES ");
            if (token.system != null) {
                whereClause.append(" c ").append(".`system` = \"").append(token.system).append("\" AND ");
            }
            whereClause.append(" c ").append(".`value` = \"").append(token.code).append("\" END");
        }

        return whereClause.toString();
    }

    public static ConceptInfo getConceptInfo(String path , String resourceType , RuntimeResourceDefinition def){
        boolean isCodableConcept = false;
        boolean isArray = false;
        boolean isPrimitive = false;
        BaseRuntimeElementDefinition<?> current = def;
        try{
            String fhirPath = path.replaceFirst("^" + resourceType + "\\.", "");
            String[] pathParts = fhirPath.split("\\.");
            for (String part : pathParts) {
                if (def != null) {
                    BaseRuntimeChildDefinition child = ((BaseRuntimeElementCompositeDefinition<?>) def).getChildByName(part);
                    if (child != null) {
                        if (child.getMax() == -1) {
                            isArray = true;
                        }
                        if(child.getChildByName(part).getImplementingClass().getSimpleName().equalsIgnoreCase("CodeableConcept")){
                            isCodableConcept = true;
                        }

                        current = child.getChildByName(part);
                        if (current.isStandardType() && !(current instanceof BaseRuntimeElementCompositeDefinition)) {
                            isPrimitive = true;
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println( e.getMessage() );
        }

        return new ConceptInfo(isCodableConcept , isArray , isPrimitive);
    }


    private static String toCouchbasePath(String fhirPath, String resourceType , boolean codableConcept , boolean isArray , boolean isPrimitive) {
        if (fhirPath == null) {
            throw new IllegalArgumentException("FHIRPath is null ");
        }

        if (!fhirPath.startsWith(resourceType + ".") && !fhirPath.startsWith("Resource.")) {
            throw new IllegalArgumentException("Invalid FHIRPath: " + fhirPath);
        }
        String subPath = "";
        if(fhirPath.contains("Resource")){
            subPath = fhirPath.substring(9);
        }else{
            subPath =  fhirPath.substring(resourceType.length() + 1);
        }


        String jsonPath = subPath
                .replace(".coding", "")
                .replace(".value", "")
                .replace(".code", "")
                .replace(".system", "");

        if(isPrimitive){
            return jsonPath;
        }

        if (codableConcept && !isArray) {
            jsonPath += ".coding";
        }
        return jsonPath;
    }

}
