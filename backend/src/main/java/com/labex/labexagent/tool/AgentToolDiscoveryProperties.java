package com.labex.labexagent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 高频工具定位与恢复提示的可调上限。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.tool-discovery")
public class AgentToolDiscoveryProperties {
    private int readFileCandidateLimit = 3;

    public int getReadFileCandidateLimit() {
        return readFileCandidateLimit;
    }

    public void setReadFileCandidateLimit(int value) {
        readFileCandidateLimit = Math.max(1, Math.min(10, value));
    }
}