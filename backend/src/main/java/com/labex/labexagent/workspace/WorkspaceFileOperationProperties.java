package com.labex.labexagent.workspace;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 用户侧工作区文件操作（上传/复制/移动/下载/搜索/图片预览/文件历史）的可调参数。
 * 默认值写在字段初始化处，可通过 labex-agent.file-ops.* 配置或环境变量覆盖。
 * 所有上限都是云端多租户下的资源滥用防线，调整前先评估磁盘/CPU 影响。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.file-ops")
public class WorkspaceFileOperationProperties {

    /** 单个上传文件大小上限。 */
    private long uploadMaxBytesPerFile = 20L * 1024 * 1024;
    /** 单次上传请求文件数上限。 */
    private int uploadMaxFilesPerRequest = 50;
    /** 单次上传请求总字节数上限。 */
    private long uploadMaxBytesPerRequest = 100L * 1024 * 1024;
    /** 每用户每分钟上传请求数。 */
    private int uploadRatePerMinute = 10;

    /** 复制操作允许的最大总字节数（递归统计）。 */
    private long transferMaxTotalBytes = 100L * 1024 * 1024;
    /** 复制目录允许的最大深度。 */
    private int transferMaxDepth = 16;
    /** 复制操作允许的最大文件数。 */
    private int transferMaxFiles = 2000;
    /** 每用户每分钟复制/移动请求数。移动是同卷 rename，不受字节数限制但仍受限流约束。 */
    private int transferRatePerMinute = 20;
    /** 全局同时进行的复制任务数。 */
    private int transferConcurrency = 2;

    /** 搜索整体墙钟超时。 */
    private long searchTimeoutMillis = 8_000L;
    /** 搜索结果条数上限。 */
    private int searchMaxResults = 200;
    /** 全局同时进行的搜索数。 */
    private int searchConcurrency = 3;
    /** 每用户每分钟搜索请求数。 */
    private int searchRatePerMinute = 30;

    /** 图片预览单文件大小上限。 */
    private long imageMaxBytes = 20L * 1024 * 1024;
    /** 每用户每分钟图片预览请求数。 */
    private int imageRatePerMinute = 60;

    /** 下载内容字节预算（zip 流中途超出会中止）。 */
    private long downloadMaxBytes = 200L * 1024 * 1024;
    /** 全局同时进行的下载任务数。 */
    private int downloadConcurrency = 2;
    /** 每用户每分钟下载请求数。 */
    private int downloadRatePerMinute = 10;

    /** 文件历史分页大小默认值（服务端硬性钳制在 1..100）。 */
    private int historyPageSize = 50;

    /** 异步导出：全局同时进行的打包任务数。 */
    private int exportConcurrency = 2;
    /** 每用户每分钟可创建的导出任务数。 */
    private int exportRatePerMinute = 5;
    /** 导出预检与打包的字节总上限（含"包含全部文件"模式）。 */
    private long exportMaxTotalBytes = 512L * 1024 * 1024;
    /** 导出成品 zip 在磁盘上的保留时长（超时由定时清理删除）。 */
    private int exportJobTtlMinutes = 30;
    /** RUNNING 状态超过该时长且无进度更新，视为僵死任务标记失败。 */
    private int exportStaleJobTimeoutMinutes = 15;
    /** 导出成品临时目录；生产环境经 application.yml 绑定到 ${LABEX_AGENT_UPLOAD_PATH}/exports。 */
    private String exportStorageDir = "./uploads/exports";
    /** 默认排除的依赖/构建产物目录名（用户可在 UI 选择"包含全部文件"跳过排除）。 */
    private List<String> exportExcludedDirectoryNames = List.of(
            "node_modules", "bower_components", "vendor",
            "dist", "build", "target", "out", "coverage",
            "__pycache__", ".pytest_cache", ".mypy_cache",
            ".next", ".nuxt", ".svelte-kit", ".vite",
            ".gradle", ".mvn");

    public long getUploadMaxBytesPerFile() {
        return uploadMaxBytesPerFile;
    }

    public void setUploadMaxBytesPerFile(long value) {
        uploadMaxBytesPerFile = value;
    }

    public int getUploadMaxFilesPerRequest() {
        return uploadMaxFilesPerRequest;
    }

    public void setUploadMaxFilesPerRequest(int value) {
        uploadMaxFilesPerRequest = clamp(value, 1, 200);
    }

    public long getUploadMaxBytesPerRequest() {
        return uploadMaxBytesPerRequest;
    }

    public void setUploadMaxBytesPerRequest(long value) {
        uploadMaxBytesPerRequest = value;
    }

    public int getUploadRatePerMinute() {
        return uploadRatePerMinute;
    }

    public void setUploadRatePerMinute(int value) {
        uploadRatePerMinute = clamp(value, 1, 120);
    }

    public long getTransferMaxTotalBytes() {
        return transferMaxTotalBytes;
    }

    public void setTransferMaxTotalBytes(long value) {
        transferMaxTotalBytes = value;
    }

    public int getTransferMaxDepth() {
        return transferMaxDepth;
    }

    public void setTransferMaxDepth(int value) {
        transferMaxDepth = clamp(value, 1, 64);
    }

    public int getTransferMaxFiles() {
        return transferMaxFiles;
    }

    public void setTransferMaxFiles(int value) {
        transferMaxFiles = clamp(value, 1, 50_000);
    }

    public int getTransferRatePerMinute() {
        return transferRatePerMinute;
    }

    public void setTransferRatePerMinute(int value) {
        transferRatePerMinute = clamp(value, 1, 120);
    }

    public int getTransferConcurrency() {
        return transferConcurrency;
    }

    public void setTransferConcurrency(int value) {
        transferConcurrency = clamp(value, 1, 16);
    }

    public long getSearchTimeoutMillis() {
        return searchTimeoutMillis;
    }

    public void setSearchTimeoutMillis(long value) {
        searchTimeoutMillis = clamp(value, 1_000L, 60_000L);
    }

    public int getSearchMaxResults() {
        return searchMaxResults;
    }

    public void setSearchMaxResults(int value) {
        searchMaxResults = clamp(value, 1, 1_000);
    }

    public int getSearchConcurrency() {
        return searchConcurrency;
    }

    public void setSearchConcurrency(int value) {
        searchConcurrency = clamp(value, 1, 16);
    }

    public int getSearchRatePerMinute() {
        return searchRatePerMinute;
    }

    public void setSearchRatePerMinute(int value) {
        searchRatePerMinute = clamp(value, 1, 120);
    }

    public long getImageMaxBytes() {
        return imageMaxBytes;
    }

    public void setImageMaxBytes(long value) {
        imageMaxBytes = value;
    }

    public int getImageRatePerMinute() {
        return imageRatePerMinute;
    }

    public void setImageRatePerMinute(int value) {
        imageRatePerMinute = clamp(value, 1, 240);
    }

    public long getDownloadMaxBytes() {
        return downloadMaxBytes;
    }

    public void setDownloadMaxBytes(long value) {
        downloadMaxBytes = value;
    }

    public int getDownloadConcurrency() {
        return downloadConcurrency;
    }

    public void setDownloadConcurrency(int value) {
        downloadConcurrency = clamp(value, 1, 16);
    }

    public int getDownloadRatePerMinute() {
        return downloadRatePerMinute;
    }

    public void setDownloadRatePerMinute(int value) {
        downloadRatePerMinute = clamp(value, 1, 120);
    }

    public int getHistoryPageSize() {
        return historyPageSize;
    }

    public void setHistoryPageSize(int value) {
        historyPageSize = clamp(value, 1, 100);
    }

    public int getExportConcurrency() {
        return exportConcurrency;
    }

    public void setExportConcurrency(int value) {
        exportConcurrency = clamp(value, 1, 16);
    }

    public int getExportRatePerMinute() {
        return exportRatePerMinute;
    }

    public void setExportRatePerMinute(int value) {
        exportRatePerMinute = clamp(value, 1, 120);
    }

    public long getExportMaxTotalBytes() {
        return exportMaxTotalBytes;
    }

    public void setExportMaxTotalBytes(long value) {
        exportMaxTotalBytes = value;
    }

    public int getExportJobTtlMinutes() {
        return exportJobTtlMinutes;
    }

    public void setExportJobTtlMinutes(int value) {
        exportJobTtlMinutes = clamp(value, 1, 24 * 60);
    }

    public int getExportStaleJobTimeoutMinutes() {
        return exportStaleJobTimeoutMinutes;
    }

    public void setExportStaleJobTimeoutMinutes(int value) {
        exportStaleJobTimeoutMinutes = clamp(value, 1, 24 * 60);
    }

    public String getExportStorageDir() {
        return exportStorageDir;
    }

    public void setExportStorageDir(String value) {
        exportStorageDir = value == null || value.isBlank() ? "./uploads/exports" : value.trim();
    }

    public List<String> getExportExcludedDirectoryNames() {
        return exportExcludedDirectoryNames;
    }

    public void setExportExcludedDirectoryNames(List<String> value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        exportExcludedDirectoryNames = List.copyOf(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
