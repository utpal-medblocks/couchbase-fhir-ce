package com.couchbase.admin.fhirBucket.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

/**
 * Detailed status tracking for FHIR bucket conversion operation
 */
public class FhirConversionStatusDetail {
    private String operationId;
    private String bucketName;
    private FhirConversionStatus status;
    private String currentStep;
    private String currentStepDescription;
    private int totalSteps;
    private int completedSteps;
    private int progressPercentage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<String> completedOperations;
    private String errorMessage;

    public FhirConversionStatusDetail() {
        this.completedOperations = new ArrayList<>();
    }

    public FhirConversionStatusDetail(String operationId, String bucketName) {
        this();
        this.operationId = operationId;
        this.bucketName = bucketName;
        this.status = FhirConversionStatus.INITIATED;
        this.startedAt = LocalDateTime.now();
        this.totalSteps = 6; // Based on our YAML conversion steps
        this.completedSteps = 0;
        this.progressPercentage = 0;
    }

    // Getters and Setters
    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public FhirConversionStatus getStatus() {
        return status;
    }

    public void setStatus(FhirConversionStatus status) {
        this.status = status;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getCurrentStepDescription() {
        return currentStepDescription;
    }

    public void setCurrentStepDescription(String currentStepDescription) {
        this.currentStepDescription = currentStepDescription;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(int totalSteps) {
        this.totalSteps = totalSteps;
    }

    public int getCompletedSteps() {
        return completedSteps;
    }

    public void setCompletedSteps(int completedSteps) {
        this.completedSteps = completedSteps;
        this.progressPercentage = totalSteps > 0 ? (completedSteps * 100) / totalSteps : 0;
    }

    public int getProgressPercentage() {
        return progressPercentage;
    }

    public void setProgressPercentage(int progressPercentage) {
        this.progressPercentage = progressPercentage;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public List<String> getCompletedOperations() {
        return completedOperations;
    }

    public void setCompletedOperations(List<String> completedOperations) {
        this.completedOperations = completedOperations;
    }

    public void addCompletedOperation(String operation) {
        this.completedOperations.add(operation);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
