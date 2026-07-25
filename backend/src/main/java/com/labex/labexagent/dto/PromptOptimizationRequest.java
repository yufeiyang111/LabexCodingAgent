package com.labex.labexagent.dto;

public class PromptOptimizationRequest {
    private String message;
    private String activePath;
    private Integer modelConfigId;

    public PromptOptimizationRequest() {
    }

    public PromptOptimizationRequest(String message, String activePath, Integer modelConfigId) {
        this.message = message;
        this.activePath = activePath;
        this.modelConfigId = modelConfigId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getActivePath() {
        return activePath;
    }

    public void setActivePath(String activePath) {
        this.activePath = activePath;
    }

    public Integer getModelConfigId() {
        return modelConfigId;
    }

    public void setModelConfigId(Integer modelConfigId) {
        this.modelConfigId = modelConfigId;
    }
}
