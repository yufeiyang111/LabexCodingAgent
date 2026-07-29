package com.labex.labexagent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Agent 主循环预算与死循环防护配置。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.loop")
public class AgentLoopProperties {
    /** 0 表示不设置任意总轮数上限，仅保留循环检测、取消、超时和上下文预算。 */
    private int hardMaxIterations;
    private int repeatedToolCallThreshold = 3;
    private int maxCycleLength = 3;
    private int maxAutomaticStrategySwitches = 1;
    private int maxNonProgressIterations = 8;

    public int getHardMaxIterations() {
        return hardMaxIterations;
    }

    public void setHardMaxIterations(int value) {
        hardMaxIterations = Math.max(0, Math.min(10_000, value));
    }

    public int getRepeatedToolCallThreshold() {
        return repeatedToolCallThreshold;
    }

    public void setRepeatedToolCallThreshold(int value) {
        repeatedToolCallThreshold = Math.max(2, Math.min(10, value));
    }

    public int getMaxCycleLength() {
        return maxCycleLength;
    }

    public void setMaxCycleLength(int value) {
        maxCycleLength = Math.max(1, Math.min(10, value));
    }

    public int getMaxAutomaticStrategySwitches() {
        return maxAutomaticStrategySwitches;
    }

    public void setMaxAutomaticStrategySwitches(int value) {
        maxAutomaticStrategySwitches = Math.max(0, Math.min(5, value));
    }

    public int getMaxNonProgressIterations() {
        return maxNonProgressIterations;
    }

    public void setMaxNonProgressIterations(int value) {
        maxNonProgressIterations = Math.max(1, Math.min(100, value));
    }
}