package com.couchbase.fhir.resources.util;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.StringType;

import java.util.ArrayList;
import java.util.List;

public class StringSearchHelper {

    public static String buildStringWhereCluse(FhirContext fhirContext,String resourceType,String paramName,String value){
        StringBuilder sb = new StringBuilder();

        List<String> conditions = getSubFields(fhirContext, resourceType, paramName , value);
        if(conditions.isEmpty()) return null;
        sb.append("ANY ").append("elem")
                .append(" IN ").append(paramName)
                .append(" SATISFIES ");
        sb.append(String.join(" OR ", conditions));
        sb.append(" END");
        return sb.toString();
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
