package com.labex.labexagent.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文压缩（compaction）预算与尺寸上限。默认值对齐 OpenCode {@code session/compaction.ts} 的
 * {@code SUMMARY_OUTPUT_TOKENS} 语义，其余为本项目 JSON 强约束路径与脱敏裁剪所需的边界。
 *
 * <p>集中的原因见 AGENTS.md 第 8.2 节：所有可调参数必须收敛到 {@code @ConfigurationProperties}，
 * 禁止散落在业务类里当内联字面量。用户可通过 {@code labex-agent.compaction.*} 环境变量覆盖。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.compaction")
public class CompactionProperties {
    /** 摘要模型最大输出 token。上游 SUMMARY_OUTPUT_TOKENS = 4096；本项目默认 8192 以适配 JSON 六字段输出。 */
    private int summaryMaxOutputTokens = 8_192;
    /** 摘要请求允许的最小输出 token，避免 provider 配置过小时被压成 0。 */
    private int summaryMinOutputTokens = 256;
    /** 单个 checkpoint 正文（含摘要与各段列表）最大字符数。 */
    private int maxSummaryChars = 8_000;
    /** 单段列表最多条目数。 */
    private int maxListItems = 10;
    /** 单段列表单条最大字符数。 */
    private int maxItemChars = 320;
    /** 摘要最短长度；短于此长度视为模型未产出有效摘要。 */
    private int minSummaryChars = 12;
    /** 默认摘要输入历史预算（字符）。 */
    private int defaultSourceChars = 36_000;
    /** 摘要输入历史预算下限（字符）。 */
    private int minSourceChars = 8_000;
    /** 摘要输入历史预算上限（字符）。 */
    private int maxSourceChars = 80_000;
    /** 按模型输入容量放大历史预算的系数。 */
    private int sourceWindowMultiplier = 2;
    /** Task 段落字符预算。 */
    private int taskChars = 1_000;
    /** stage 段落字符预算。 */
    private int stageChars = 200;
    /** plan 段落字符预算。 */
    private int planChars = 1_200;
    /** 未验证变更目标段落字符预算。 */
    private int unverifiedTargetsChars = 900;
    /** 视为"最近"的历史消息条数，决定单条预算档位。 */
    private int recentHistoryItems = 6;
    /** 最近历史消息单条字符预算。 */
    private int recentHistoryItemChars = 3_600;
    /** 更早历史消息单条字符预算。 */
    private int olderHistoryItemChars = 1_000;
    /** checkpoint 头部标注的模型名字符预算。 */
    private int modelNameChars = 180;
    /** Markdown 结构化摘要路径的额外字符余量。 */
    private int markdownExtraChars = 2_000;
    /** checkpoint 渲染后的整体余量（超出 maxSummaryChars + 该值即判定失败）。 */
    private int checkpointBudgetSlack = 3_000;
    /** 解析失败时写入日志的原文截断长度。 */
    private int invalidSnippetChars = 200;

    public int getSummaryMaxOutputTokens() {
        return summaryMaxOutputTokens;
    }

    public void setSummaryMaxOutputTokens(int value) {
        summaryMaxOutputTokens = Math.max(256, Math.min(64_000, value));
    }

    public int getSummaryMinOutputTokens() {
        return summaryMinOutputTokens;
    }

    public void setSummaryMinOutputTokens(int value) {
        summaryMinOutputTokens = Math.max(1, Math.min(4_096, value));
    }

    public int getMaxSummaryChars() {
        return maxSummaryChars;
    }

    public void setMaxSummaryChars(int value) {
        maxSummaryChars = Math.max(512, Math.min(64_000, value));
    }

    public int getMaxListItems() {
        return maxListItems;
    }

    public void setMaxListItems(int value) {
        maxListItems = Math.max(1, Math.min(50, value));
    }

    public int getMaxItemChars() {
        return maxItemChars;
    }

    public void setMaxItemChars(int value) {
        maxItemChars = Math.max(32, Math.min(4_000, value));
    }

    public int getMinSummaryChars() {
        return minSummaryChars;
    }

    public void setMinSummaryChars(int value) {
        minSummaryChars = Math.max(1, Math.min(1_000, value));
    }

    public int getDefaultSourceChars() {
        return defaultSourceChars;
    }

    public void setDefaultSourceChars(int value) {
        defaultSourceChars = Math.max(1_000, Math.min(200_000, value));
    }

    public int getMinSourceChars() {
        return minSourceChars;
    }

    public void setMinSourceChars(int value) {
        minSourceChars = Math.max(1_000, Math.min(200_000, value));
    }

    public int getMaxSourceChars() {
        return maxSourceChars;
    }

    public void setMaxSourceChars(int value) {
        maxSourceChars = Math.max(minSourceChars, Math.min(400_000, value));
    }

    public int getSourceWindowMultiplier() {
        return sourceWindowMultiplier;
    }

    public void setSourceWindowMultiplier(int value) {
        sourceWindowMultiplier = Math.max(1, Math.min(8, value));
    }

    public int getTaskChars() {
        return taskChars;
    }

    public void setTaskChars(int value) {
        taskChars = Math.max(64, Math.min(8_000, value));
    }

    public int getStageChars() {
        return stageChars;
    }

    public void setStageChars(int value) {
        stageChars = Math.max(16, Math.min(2_000, value));
    }

    public int getPlanChars() {
        return planChars;
    }

    public void setPlanChars(int value) {
        planChars = Math.max(64, Math.min(8_000, value));
    }

    public int getUnverifiedTargetsChars() {
        return unverifiedTargetsChars;
    }

    public void setUnverifiedTargetsChars(int value) {
        unverifiedTargetsChars = Math.max(64, Math.min(8_000, value));
    }

    public int getRecentHistoryItems() {
        return recentHistoryItems;
    }

    public void setRecentHistoryItems(int value) {
        recentHistoryItems = Math.max(1, Math.min(50, value));
    }

    public int getRecentHistoryItemChars() {
        return recentHistoryItemChars;
    }

    public void setRecentHistoryItemChars(int value) {
        recentHistoryItemChars = Math.max(128, Math.min(20_000, value));
    }

    public int getOlderHistoryItemChars() {
        return olderHistoryItemChars;
    }

    public void setOlderHistoryItemChars(int value) {
        olderHistoryItemChars = Math.max(64, Math.min(20_000, value));
    }

    public int getModelNameChars() {
        return modelNameChars;
    }

    public void setModelNameChars(int value) {
        modelNameChars = Math.max(16, Math.min(1_000, value));
    }

    public int getMarkdownExtraChars() {
        return markdownExtraChars;
    }

    public void setMarkdownExtraChars(int value) {
        markdownExtraChars = Math.max(0, Math.min(20_000, value));
    }

    public int getCheckpointBudgetSlack() {
        return checkpointBudgetSlack;
    }

    public void setCheckpointBudgetSlack(int value) {
        checkpointBudgetSlack = Math.max(0, Math.min(20_000, value));
    }

    public int getInvalidSnippetChars() {
        return invalidSnippetChars;
    }

    public void setInvalidSnippetChars(int value) {
        invalidSnippetChars = Math.max(16, Math.min(4_000, value));
    }
}
