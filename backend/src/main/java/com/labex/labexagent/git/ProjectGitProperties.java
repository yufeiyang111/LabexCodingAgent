package com.labex.labexagent.git;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 项目版本库（用户 git 提交）的可调参数。
 *
 * <p>默认值写在字段初始化处，可通过 {@code labex-agent.project-git.*} 或环境变量覆盖。
 * 所有超时都是 git 子进程的墙钟上限：超时即失败并返回明确错误，不静默降级。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.project-git")
public class ProjectGitProperties {

    /** 只读查询（status / log / diff）超时。 */
    private int queryTimeoutSeconds = 60;
    /** 写操作（stage / commit / revert）超时；大仓库提交需要更长时间。 */
    private int writeTimeoutSeconds = 180;
    /** 历史列表单页上限。 */
    private int commitsPageSize = 50;
    /** 单次返回的未提交改动条目上限；超过时只截断列表，不阻断提交。 */
    private int statusMaxEntries = 2000;
    /** 单个文件 diff 的字节上限。 */
    private int diffMaxBytes = 400_000;
    /** clone URL 里短期令牌的有效期。 */
    private int cloneTokenTtlMinutes = 60;
    /**
     * {@code git-http-backend} 的绝对路径。留空时按 {@code GIT_EXEC_PATH}、{@code PATH} 查找。
     * 仅当 git 装在非标准位置时才需要设置。
     */
    private String httpBackendPath = "";

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int value) {
        queryTimeoutSeconds = clamp(value, 5, 600);
    }

    public int getWriteTimeoutSeconds() {
        return writeTimeoutSeconds;
    }

    public void setWriteTimeoutSeconds(int value) {
        writeTimeoutSeconds = clamp(value, 10, 1800);
    }

    public int getCommitsPageSize() {
        return commitsPageSize;
    }

    public void setCommitsPageSize(int value) {
        commitsPageSize = clamp(value, 1, 200);
    }

    public int getStatusMaxEntries() {
        return statusMaxEntries;
    }

    public void setStatusMaxEntries(int value) {
        statusMaxEntries = clamp(value, 100, 20_000);
    }

    public int getDiffMaxBytes() {
        return diffMaxBytes;
    }

    public void setDiffMaxBytes(int value) {
        diffMaxBytes = clamp(value, 4_000, 4_000_000);
    }

    public int getCloneTokenTtlMinutes() {
        return cloneTokenTtlMinutes;
    }

    public void setCloneTokenTtlMinutes(int value) {
        cloneTokenTtlMinutes = clamp(value, 1, 1440);
    }

    public String getHttpBackendPath() {
        return httpBackendPath;
    }

    public void setHttpBackendPath(String value) {
        httpBackendPath = value == null ? "" : value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
