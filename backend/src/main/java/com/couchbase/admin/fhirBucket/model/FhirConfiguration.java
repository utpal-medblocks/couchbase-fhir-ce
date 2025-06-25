package com.couchbase.admin.fhirBucket.model;

import java.util.Map;
import java.util.List;

/**
 * Root configuration class that maps the YAML structure
 */
public class FhirConfiguration {
    private FhirSettings fhir;
    private ConversionSettings conversion;

    public FhirSettings getFhir() {
        return fhir;
    }

    public void setFhir(FhirSettings fhir) {
        this.fhir = fhir;
    }

    public ConversionSettings getConversion() {
        return conversion;
    }

    public void setConversion(ConversionSettings conversion) {
        this.conversion = conversion;
    }

    public static class FhirSettings {
        private Map<String, ScopeConfiguration> scopes;
        private List<BuildCommand> buildCommands;

        public Map<String, ScopeConfiguration> getScopes() {
            return scopes;
        }

        public void setScopes(Map<String, ScopeConfiguration> scopes) {
            this.scopes = scopes;
        }

        public List<BuildCommand> getBuildCommands() {
            return buildCommands;
        }

        public void setBuildCommands(List<BuildCommand> buildCommands) {
            this.buildCommands = buildCommands;
        }
    }

    public static class ScopeConfiguration {
        private String name;
        private String description;
        private List<CollectionConfiguration> collections;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<CollectionConfiguration> getCollections() {
            return collections;
        }

        public void setCollections(List<CollectionConfiguration> collections) {
            this.collections = collections;
        }
    }

    public static class CollectionConfiguration {
        private String name;
        private String description;
        private List<IndexConfiguration> indexes;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<IndexConfiguration> getIndexes() {
            return indexes;
        }

        public void setIndexes(List<IndexConfiguration> indexes) {
            this.indexes = indexes;
        }
    }

    public static class IndexConfiguration {
        private String name;
        private String type;
        private String sql;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }
    }

    public static class ConversionSettings {
        private List<ConversionStep> steps;

        public List<ConversionStep> getSteps() {
            return steps;
        }

        public void setSteps(List<ConversionStep> steps) {
            this.steps = steps;
        }
    }

    public static class ConversionStep {
        private String name;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class BuildCommand {
        private String name;
        private String description;
        private String query;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }
}
