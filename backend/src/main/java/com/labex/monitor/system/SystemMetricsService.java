package com.labex.monitor.system;

import java.io.File;
import java.lang.management.ManagementFactory;
import org.springframework.stereotype.Service;

/** 系统资源采集：仅依赖 JDK 自带 API，不引入外部依赖。 */
@Service
public class SystemMetricsService {

    public SystemSnapshot snapshot() {
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Runtime runtime = Runtime.getRuntime();

        SystemSnapshot snapshot = new SystemSnapshot();
        snapshot.setCpuPercent(round1(safePercent(osBean.getCpuLoad())));
        snapshot.setMemoryTotalBytes(osBean.getTotalMemorySize());
        snapshot.setMemoryUsedBytes(Math.max(0, osBean.getTotalMemorySize() - osBean.getFreeMemorySize()));
        snapshot.setMemoryPercent(round1(percent(snapshot.getMemoryUsedBytes(), snapshot.getMemoryTotalBytes())));

        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        snapshot.setHeapUsedBytes(heapUsed);
        snapshot.setHeapMaxBytes(runtime.maxMemory());

        File root = File.listRoots()[0];
        snapshot.setDiskTotalBytes(root.getTotalSpace());
        snapshot.setDiskUsedBytes(Math.max(0, root.getTotalSpace() - root.getUsableSpace()));
        snapshot.setDiskPercent(round1(percent(snapshot.getDiskUsedBytes(), snapshot.getDiskTotalBytes())));

        snapshot.setUptimeSeconds(ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        snapshot.setAvailableProcessors(runtime.availableProcessors());
        snapshot.setSystemLoadAverage(osBean.getSystemLoadAverage());
        snapshot.setOsName(System.getProperty("os.name", "unknown"));
        snapshot.setOsArch(System.getProperty("os.arch", "unknown"));
        snapshot.setJavaVersion(System.getProperty("java.version", "unknown"));
        snapshot.setProcessPid(ProcessHandle.current().pid());
        return snapshot;
    }

    private static double safePercent(double fraction) {
        if (Double.isNaN(fraction) || fraction < 0) {
            return 0;
        }
        return fraction * 100;
    }

    private static double percent(long used, long total) {
        if (total <= 0) {
            return 0;
        }
        return (double) used * 100 / total;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}