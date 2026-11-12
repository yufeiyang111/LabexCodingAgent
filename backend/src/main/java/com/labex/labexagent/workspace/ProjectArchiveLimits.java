package com.labex.labexagent.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 项目压缩包上传的配置。三处判定共用同一份上限：
 *
 * <ol>
 *   <li>Spring multipart 的 {@code max-file-size} / {@code max-request-size}（application.yml 引用同一环境变量）；</li>
 *   <li>Caddy 的 {@code request_body max_size}（同一环境变量）；</li>
 *   <li>分片会话的累计字节与解压阶段的压缩包字节、解压后总字节与文件数。</li>
 * </ol>
 *
 * <p>浏览器把压缩包切成 {@code chunkSizeBytes} 的分片逐片上传，因此单次 HTTP 请求体只有几 MB。
 * 这是为了穿过反向代理与 CDN 的单请求体限制（Cloudflare Free/Pro 为 100 MB），
 * 让 {@code maxArchiveBytes} 真正可达，而不是停在网关那一层。
 *
 * <p>前端通过 {@code GET /student/projects/archive-limits} 读取同一份值，不硬编码任何数字。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.project-archive")
public class ProjectArchiveLimits {

    /** 压缩包字节上限。默认 500 MiB。 */
    private long maxArchiveBytes = 500L * 1024 * 1024;

    /** 解压后总字节上限。默认 2 GiB；压缩比通常 3-6 倍，留足余量。 */
    private long maxExtractedBytes = 2L * 1024 * 1024 * 1024;

    /** 解压后文件数上限。默认 50,000；带 node_modules 的项目常达数万。 */
    private int maxExtractedFiles = 50_000;

    /** 是否默认跳过依赖与构建产物目录。用户可在上传对话框取消勾选。 */
    private boolean skipDependencyDirectoriesByDefault = true;

    /** 单个分片的字节上限。默认 8 MiB，远低于任何 CDN 的单请求体限制。 */
    private long chunkSizeBytes = 8L * 1024 * 1024;

    /** 分片与待导入压缩包的暂存目录；导入结束即删除。 */
    private String stagingDirectory = "";

    /** 上传会话在无活动多久后失效；过期会话连同暂存文件一起清理。 */
    private long sessionTtlMinutes = 120L;

    /** 接受上传前要求的最小可用磁盘余量，避免解压到一半磁盘写满。 */
    private long minFreeDiskBytes = 1024L * 1024 * 1024;

    /** 同时存在（含导入中）的上传会话上限，防止暂存目录被无限占满。 */
    private int maxConcurrentSessions = 200;

    public long getMaxArchiveBytes() {
        return maxArchiveBytes;
    }

    public void setMaxArchiveBytes(long value) {
        maxArchiveBytes = Math.max(1024L * 1024, value);
    }

    public long getMaxExtractedBytes() {
        return maxExtractedBytes;
    }

    public void setMaxExtractedBytes(long value) {
        maxExtractedBytes = Math.max(1024L * 1024, value);
    }

    public int getMaxExtractedFiles() {
        return maxExtractedFiles;
    }

    public void setMaxExtractedFiles(int value) {
        maxExtractedFiles = Math.max(1, value);
    }

    public boolean isSkipDependencyDirectoriesByDefault() {
        return skipDependencyDirectoriesByDefault;
    }

    public void setSkipDependencyDirectoriesByDefault(boolean value) {
        skipDependencyDirectoriesByDefault = value;
    }

    public long getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(long value) {
        chunkSizeBytes = Math.max(1024L, value);
    }

    public String getStagingDirectory() {
        return stagingDirectory;
    }

    public void setStagingDirectory(String value) {
        stagingDirectory = value == null ? "" : value.trim();
    }

    public long getSessionTtlMinutes() {
        return sessionTtlMinutes;
    }

    public void setSessionTtlMinutes(long value) {
        sessionTtlMinutes = Math.max(1L, value);
    }

    public long getMinFreeDiskBytes() {
        return minFreeDiskBytes;
    }

    public void setMinFreeDiskBytes(long value) {
        minFreeDiskBytes = Math.max(0L, value);
    }

    public int getMaxConcurrentSessions() {
        return maxConcurrentSessions;
    }

    public void setMaxConcurrentSessions(int value) {
        maxConcurrentSessions = Math.max(1, value);
    }
}
