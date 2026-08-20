package com.labex.labexagent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Agent 主循环预算与死循环防护配置。 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.loop")
public class AgentLoopProperties {
    /** 0 表示不设置任意总轮数上限，仅保留循环检测、取消、超时和上下文预算。 */
    private int hardMaxIterations;
    /** 0 表示禁用软哨兵；超过该轮数后禁用工具并强制模型输出文本总结收尾。 */
    private int softMaxIterations;
    private int repeatedToolCallThreshold = 3;
    private int maxCycleLength = 3;
    private int maxAutomaticStrategySwitches = 1;
    private int maxNonProgressIterations = 8;
    /** 同一 durable 完成证据允许的最终答复自动纠正次数；0 表示直接失败。 */
    private int finalizationRecoveryLimit = 1;
    /** 默认保留的最近完整对话轮数 (对齐 OpenCode DEFAULT_TAIL_TURNS = 2)。 */
    private int tailTurns = 2;
    /** 尾部保护 Token 预算下限 (对齐 OpenCode MIN_PRESERVE_RECENT_TOKENS = 2_000)。 */
    private int minPreserveRecentTokens = 2000;
    /** 尾部保护 Token 预算上限 (对齐 OpenCode MAX_PRESERVE_RECENT_TOKENS = 8_000)。 */
    private int maxPreserveRecentTokens = 8000;

    public int getTailTurns() {
        return tailTurns;
    }

    public void setTailTurns(int value) {
        tailTurns = Math.max(1, Math.min(20, value));
    }

    public int getMinPreserveRecentTokens() {
        return minPreserveRecentTokens;
    }

    public void setMinPreserveRecentTokens(int value) {
        minPreserveRecentTokens = Math.max(500, Math.min(50_000, value));
    }

    public int getMaxPreserveRecentTokens() {
        return maxPreserveRecentTokens;
    }

    public void setMaxPreserveRecentTokens(int value) {
        maxPreserveRecentTokens = Math.max(minPreserveRecentTokens, Math.min(100_000, value));
    }

    public int getHardMaxIterations() {
        return hardMaxIterations;
    }

    public void setHardMaxIterations(int value) {
        hardMaxIterations = Math.max(0, Math.min(10_000, value));
    }

    public int getSoftMaxIterations() {
        return softMaxIterations;
    }

    public void setSoftMaxIterations(int value) {
        softMaxIterations = Math.max(0, Math.min(10_000, value));
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

    public int getFinalizationRecoveryLimit() {
        return finalizationRecoveryLimit;
    }

    public void setFinalizationRecoveryLimit(int value) {
        finalizationRecoveryLimit = Math.max(0, Math.min(5, value));
    }
}