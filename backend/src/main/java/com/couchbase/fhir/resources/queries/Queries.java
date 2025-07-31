package com.couchbase.fhir.resources.queries;

public class Queries {

    public static final String SEARCH_QUERY_TOP_LEVEL = "SELECT * FROM `%s`.`%s`.`%s`  %s ";
    public static final String SEARCH_QUERY_REV_INCLUDE = "SELECT '%s' AS resourceType, META().id AS docId, * " +
            "FROM `fhir`.`Resources`.`%s`  " +
            "WHERE ANY ref IN %s SATISFIES ref.reference IN (" +
            "SELECT RAW \"%s/\" || id" +
            "  FROM `fhir`.`Resources`.`%s` "+
            " where %s"+
            ") END";
}
