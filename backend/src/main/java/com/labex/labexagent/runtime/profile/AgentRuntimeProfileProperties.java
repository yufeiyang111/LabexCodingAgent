package com.labex.labexagent.runtime.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 对话新建时的默认运行时 profile；已有对话和任务始终以持久化 snapshot 为准。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.runtime-profile")
public class AgentRuntimeProfileProperties {
    private String defaultProfile = AgentRuntimeProfile.LABEX_LEGACY.persistedValue();

    public String getDefaultProfile() {
        return defaultProfile;
    }

    public void setDefaultProfile(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("labex-agent.runtime-profile.default-profile is required");
        }
        defaultProfile = AgentRuntimeProfile.requireKnown(value).persistedValue();
    }

    public AgentRuntimeProfile resolveDefaultProfile() {
        return AgentRuntimeProfile.requireKnown(defaultProfile);
    }
}