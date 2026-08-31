package com.labex.labexagent.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 子代理派发、工具循环与并行上限配置。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.subagent")
public class AgentSubagentProperties {
    private int maxTurns = 8;
    private int maxSpawnDepth = 3;
    private int defaultTokenBudget = 4096;
    private int maxParallel = 4;
    private int digestMaxChars = 16_000;
    private int toolResultMaxChars = 8_000;
    private int priorContextMaxChars = 4_000;
    private long taskTimeoutMs = 600_000L;

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int value) {
        maxTurns = Math.max(1, Math.min(32, value));
    }

    public int getMaxSpawnDepth() {
        return maxSpawnDepth;
    }

    public void setMaxSpawnDepth(int value) {
        maxSpawnDepth = Math.max(1, Math.min(8, value));
    }

    public int getDefaultTokenBudget() {
        return defaultTokenBudget;
    }

    public void setDefaultTokenBudget(int value) {
        defaultTokenBudget = Math.max(256, Math.min(128_000, value));
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int value) {
        maxParallel = Math.max(1, Math.min(16, value));
    }

    public int getDigestMaxChars() {
        return digestMaxChars;
    }

    public void setDigestMaxChars(int value) {
        digestMaxChars = Math.max(1_000, Math.min(64_000, value));
    }

    public int getToolResultMaxChars() {
        return toolResultMaxChars;
    }

    public void setToolResultMaxChars(int value) {
        toolResultMaxChars = Math.max(500, Math.min(32_000, value));
    }

    public int getPriorContextMaxChars() {
        return priorContextMaxChars;
    }

    public void setPriorContextMaxChars(int value) {
        priorContextMaxChars = Math.max(500, Math.min(16_000, value));
    }

    public long getTaskTimeoutMs() {
        return taskTimeoutMs;
    }

    public void setTaskTimeoutMs(long value) {
        taskTimeoutMs = Math.max(10_000L, Math.min(3_600_000L, value));
    }
}
