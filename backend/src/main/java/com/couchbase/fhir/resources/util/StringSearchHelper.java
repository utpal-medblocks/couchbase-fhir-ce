package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.*;
import org.hl7.fhir.r4.model.StringType;

import java.util.ArrayList;
import java.util.List;

public class StringSearchHelper {

    public static String buildStringWhereCluse(FhirContext fhirContext, String resourceType, String paramName, String value , RuntimeSearchParam searchParam){
        String rawPath = searchParam.getPath();
        String fhirPath = rawPath.replaceFirst("^" + resourceType + "\\.", "");
        String[] pathParts = fhirPath.split("\\.");
        StringBuilder sb = new StringBuilder();
        if(pathParts.length == 1){
            List<String> conditions = getSubFields(fhirContext, resourceType, paramName , value);
            if(conditions.isEmpty()) return null;
            sb.append("ANY ").append("elem")
                    .append(" IN ").append(paramName)
                    .append(" SATISFIES ");
            sb.append(String.join(" OR ", conditions));
            sb.append(" END");
        }else{
            String condition = getSubFieldsComposite(fhirContext , resourceType , paramName , value , fhirPath);
            sb.append(condition);

        }




        return sb.toString();
    }


    public static String getSubFieldsComposite(FhirContext ctx, String resource, String fieldName , String searchValue , String fhirPath) {
        List<String> fieldConditions = new ArrayList<>();
        String[] pathParts = fhirPath.split("\\.");

        String parentField = pathParts[0];
        String childField = pathParts[1];

        String arrayAlias = "elem";
        String escapedValue = searchValue.toLowerCase().replace("\"", "\\\"");
        BaseRuntimeElementCompositeDefinition<?> resourceDef = (BaseRuntimeElementCompositeDefinition<?>) ctx.getResourceDefinition(resource);
        BaseRuntimeChildDefinition parentDef = resourceDef.getChildByName(parentField);
        BaseRuntimeElementDefinition<?> elementDef = parentDef.getChildByName(parentField);
        BaseRuntimeElementCompositeDefinition<?> compositeDef =
                (BaseRuntimeElementCompositeDefinition<?>) elementDef;
        BaseRuntimeChildDefinition childDef = compositeDef.getChildByName(childField);

        boolean isChildArray = childDef.getMax() == -1;
        String alias = "elem_" + parentField;

        if (isChildArray) {
            return String.format("ANY %s IN %s SATISFIES ANY x IN %s.%s SATISFIES LOWER(x) LIKE \"%%%s%%\" END END",
                    alias, parentField, alias, childField, escapedValue);
        } else {
            return String.format("ANY %s IN %s SATISFIES LOWER(%s.%s) LIKE \"%%%s%%\" END",
                    alias, parentField, alias, childField, escapedValue);
        }

    }


    public static List<String> getSubFields(FhirContext ctx, String resource, String fieldName , String searchValue) {
        List<String> fieldConditions = new ArrayList<>();

        String arrayAlias = "elem";
        String escapedValue = searchValue.toLowerCase().replace("\"", "\\\"");
        BaseRuntimeElementCompositeDefinition<?> resourceDef = (BaseRuntimeElementCompositeDefinition<?>) ctx.getResourceDefinition(resource);
        BaseRuntimeChildDefinition fieldChild = resourceDef.getChildByName(fieldName);

        BaseRuntimeElementDefinition<?> fieldType = fieldChild.getChildByName(fieldName);
        if (fieldType instanceof BaseRuntimeElementCompositeDefinition<?>) {
            BaseRuntimeElementCompositeDefinition<?> compDef = (BaseRuntimeElementCompositeDefinition<?>) fieldType;
            for (BaseRuntimeChildDefinition sub : compDef.getChildren()) {

                if(sub.getChildNameByDatatype(StringType.class) != null){
                    String subName= sub.getElementName();
                    if (subName.equals("id") || subName.equals("extension") || subName.equals("period") || subName.equals("use")) {
                        continue;
                    }

                    boolean isList = sub.getMax() == -1;
                    if (isList) {
                        // e.g. ANY g IN nameElem.given SATISFIES LOWER(g) LIKE "%doe%" END
                        fieldConditions.add(String.format("ANY x IN %s.%s SATISFIES LOWER(x) LIKE \"%%%s%%\" END ", arrayAlias, subName, escapedValue));
                    } else {
                        // e.g. LOWER(nameElem.family) LIKE "%doe%"
                        fieldConditions.add(String.format("LOWER(%s.%s) LIKE \"%%%s%%\"", arrayAlias, subName, escapedValue));
                    }
                }

            }
        }

        return fieldConditions;
    }
}
