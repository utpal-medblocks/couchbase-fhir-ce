package com.couchbase.fhir.resources.service;

import java.util.Set;

public class UserAuditInfo {
    private String userId;
    private String userType;
    private Set<String> roles;
    private String department;
    private String sessionId;
    private String sourceSystem;

    public UserAuditInfo() {}

    public UserAuditInfo(String userId, String userType, Set<String> roles) {
        this.userId = userId;
        this.userType = userType;
        this.roles = roles;
        this.sourceSystem = "couchbase-fhir-server";
    }

    public UserAuditInfo(String userId, String userType, Set<String> roles, String department, String sessionId, String sourceSystem) {
        this.userId = userId;
        this.userType = userType;
        this.roles = roles;
        this.department = department;
        this.sessionId = sessionId;
        this.sourceSystem = sourceSystem;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    @Override
    public String toString() {
        return "UserAuditInfo{" +
                "userId='" + userId + '\'' +
                ", userType='" + userType + '\'' +
                ", roles=" + roles +
                ", department='" + department + '\'' +
                ", sessionId='" + sessionId + '\'' +
                ", sourceSystem='" + sourceSystem + '\'' +
                '}';
    }
} 