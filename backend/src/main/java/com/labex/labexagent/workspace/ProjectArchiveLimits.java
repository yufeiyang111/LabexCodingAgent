package com.labex.labexagent.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 项目压缩包上传的上限。三处判定共用这一份配置：
 *
 * <ol>
 *   <li>Spring multipart 的 {@code max-file-size} / {@code max-request-size}（application.yml 引用同一环境变量）；</li>
 *   <li>Caddy 的 {@code request_body max_size}（同一环境变量）；</li>
 *   <li>解压阶段的压缩包字节、解压后总字节与文件数（{@link ProjectArchiveExtractor}）。</li>
 * </ol>
 *
 * <p>前两层是传输层，只能拦住"根本不该收"的请求；第三层是业务层，也是唯一能给出具体数字的地方。
 * 前端通过 {@code GET /student/projects/archive-limits} 读取同一份值，不硬编码任何数字。
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
}
