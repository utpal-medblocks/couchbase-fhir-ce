package com.couchbase.fhir.search.model;

public class TokenParam {
    public String system;
    public  String code;

    public TokenParam(String rawValue) {
        if (rawValue.contains("|")) {
            String[] parts = rawValue.split("\\|", 2);
            this.system = parts[0].isEmpty() ? null : parts[0];
            this.code = parts[1];
        } else {
            this.system = null;
            this.code = rawValue;
        }
    }

}
