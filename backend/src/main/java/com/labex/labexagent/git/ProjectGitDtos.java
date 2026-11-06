package com.labex.labexagent.git;

import java.util.List;

/**
 * 项目版本库对外返回的数据结构。全部是不可变记录，只承载展示所需字段。
 */
public final class ProjectGitDtos {
    private ProjectGitDtos() {
    }

    /** 一个未提交改动。{@code status} 是 git 的单字母状态，重命名/复制额外带 {@code oldPath}。 */
    public record StatusEntry(String path, String oldPath, String status, boolean staged) {
    }

    /** 工作区相对 HEAD 的整体状态。 */
    public record StatusView(boolean initialized, int unstagedCount, int stagedCount, List<StatusEntry> entries) {
        public static StatusView empty() {
            return new StatusView(false, 0, 0, List.of());
        }
    }

    /** 提交列表里的一行。 */
    public record CommitSummary(String sha, String shortSha, String message, String authorName,
                                String committedAt, List<String> parents) {
    }

    /** 提交列表一页。{@code nextBefore} 为 null 表示没有更多。 */
    public record CommitPage(List<CommitSummary> commits, String nextBefore) {
    }

    /** 单次提交里一个文件的改动。 */
    public record CommitFile(String path, String oldPath, String status, int additions, int deletions, boolean binary) {
    }

    /** 提交详情：元信息 + 文件清单。 */
    public record CommitDetail(CommitSummary commit, List<CommitFile> files) {
    }

    /** 一次写操作的结果，带新提交（如果产生了提交）。 */
    public record CommitResult(String sha, String shortSha, String message, int fileCount) {
    }

    /** 带短期令牌的 clone 地址。 */
    public record CloneUrl(String url, String username, String expiresAt) {
    }
}
