package com.labex.labexagent.workspace.cleanup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 临时产物（run log / tool artifact / preview log / 执行临时目录）保留策略可调参数。 */
@ConfigurationProperties(prefix = "labex-agent.agent-cleanup")
public class AgentEphemeralCleanupProperties {
    /** 总开关；关闭后调度器直接返回，不扫描任何 workspace。 */
    private boolean enabled = true;
    /** 清理调度间隔（毫秒）。 */
    private long intervalMs = 1_800_000L;
    /** .labex/agent-logs 运行日志保留天数。 */
    private int runLogRetentionDays = 7;
    /** .labex-agent/artifacts/task-N 工具输出保留天数。 */
    private int artifactRetentionDays = 7;
    /** .labex-agent/artifacts/preview 预览日志保留天数。 */
    private int previewLogRetentionDays = 3;
    /** worker-tmp / terminal-tmp / runtime 临时目录条目最长保留小时数。 */
    private int tmpMaxAgeHours = 6;
    /** 最短宽限（分钟）：任何候选文件写入后不足该时长一律跳过。 */
    private long minAgeMinutes = 30;
    /** 单轮最多处理的项目数。 */
    private int maxProjectsPerTick = 200;
    /** 单轮最多删除的顶层条目数（日志文件 / task 目录 / tmp 条目各计 1）。 */
    private int maxDeleteEntriesPerTick = 2_000;
    /** 单轮删除字节上限，达到后提前结束本轮。 */
    private long maxDeleteBytesPerTick = 128L * 1024 * 1024;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public int getRunLogRetentionDays() { return runLogRetentionDays; }
    public void setRunLogRetentionDays(int runLogRetentionDays) { this.runLogRetentionDays = runLogRetentionDays; }
    public int getArtifactRetentionDays() { return artifactRetentionDays; }
    public void setArtifactRetentionDays(int artifactRetentionDays) { this.artifactRetentionDays = artifactRetentionDays; }
    public int getPreviewLogRetentionDays() { return previewLogRetentionDays; }
    public void setPreviewLogRetentionDays(int previewLogRetentionDays) { this.previewLogRetentionDays = previewLogRetentionDays; }
    public int getTmpMaxAgeHours() { return tmpMaxAgeHours; }
    public void setTmpMaxAgeHours(int tmpMaxAgeHours) { this.tmpMaxAgeHours = tmpMaxAgeHours; }
    public long getMinAgeMinutes() { return minAgeMinutes; }
    public void setMinAgeMinutes(long minAgeMinutes) { this.minAgeMinutes = minAgeMinutes; }
    public int getMaxProjectsPerTick() { return maxProjectsPerTick; }
    public void setMaxProjectsPerTick(int maxProjectsPerTick) { this.maxProjectsPerTick = maxProjectsPerTick; }
    public int getMaxDeleteEntriesPerTick() { return maxDeleteEntriesPerTick; }
    public void setMaxDeleteEntriesPerTick(int maxDeleteEntriesPerTick) { this.maxDeleteEntriesPerTick = maxDeleteEntriesPerTick; }
    public long getMaxDeleteBytesPerTick() { return maxDeleteBytesPerTick; }
    public void setMaxDeleteBytesPerTick(long maxDeleteBytesPerTick) { this.maxDeleteBytesPerTick = maxDeleteBytesPerTick; }
}
