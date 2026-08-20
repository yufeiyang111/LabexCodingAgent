package com.labex.monitor.system;

import lombok.Data;

/** 一次采样的系统资源快照（字节单位，百分比为 0-100）。 */
@Data
public class SystemSnapshot {
    private double cpuPercent;
    private long memoryTotalBytes;
    private long memoryUsedBytes;
    private double memoryPercent;
    private long diskTotalBytes;
    private long diskUsedBytes;
    private double diskPercent;
    private long heapUsedBytes;
    private long heapMaxBytes;
    private long uptimeSeconds;
    private int availableProcessors;
    private double systemLoadAverage;
    private String osName;
    private String osArch;
    private String javaVersion;
    private long processPid;
}