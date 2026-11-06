package com.labex.labexagent.git;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 {@code git status --porcelain=v1 -z}、{@code --name-status -z} 与 {@code --numstat -z} 输出。
 *
 * <p>为什么单独成类：这些格式的细节（NUL 分隔、重命名多带一个路径字段、状态字母两列语义不同）
 * 内联进服务会同时毁掉可读性和可测性。纯函数，无 IO。
 */
final class GitPorcelainParser {
    private GitPorcelainParser() {
    }

    /**
     * 解析 {@code status --porcelain=v1 -z --untracked-files=all --find-renames}。
     *
     * <p>每个条目以 NUL 结尾；普通条目是 {@code XY<space>path}；重命名/复制是
     * {@code XY<space>newPath<NUL>oldPath}。X 是暂存区相对 HEAD 的状态，Y 是工作区相对暂存区的状态。
     * {@code ' '} 表示无改动，{@code '?'} 表示未跟踪。
     */
    static List<ProjectGitDtos.StatusEntry> parseStatus(String output, int maxEntries) {
        List<ProjectGitDtos.StatusEntry> entries = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return entries;
        }
        String[] parts = output.split("\u0000", -1);
        for (int index = 0; index < parts.length; index++) {
            String entry = parts[index];
            if (entry.length() < 4) {
                continue;
            }
            char indexStatus = entry.charAt(0);
            char workTreeStatus = entry.charAt(1);
            String path = entry.substring(3);
            String oldPath = "";
            if (isRenameOrCopy(indexStatus) || isRenameOrCopy(workTreeStatus)) {
                if (index + 1 < parts.length) {
                    oldPath = parts[++index];
                }
            }
            if (path.isBlank()) {
                continue;
            }
            if (entries.size() >= maxEntries) {
                break;
            }
            entries.add(new ProjectGitDtos.StatusEntry(path, oldPath,
                    normalizeStatus(indexStatus, workTreeStatus), indexStatus != ' ' && indexStatus != '?'));
        }
        return entries;
    }

    /**
     * 解析 {@code diff --name-status -M -z}。格式：{@code STATUS<NUL>path<NUL>}；
     * 重命名/复制是 {@code R100<NUL>oldPath<NUL>newPath<NUL>}。
     */
    static List<ProjectGitDtos.CommitFile> parseNameStatus(String output) {
        List<ProjectGitDtos.CommitFile> files = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return files;
        }
        String[] parts = output.split("\u0000", -1);
        for (int index = 0; index < parts.length; index++) {
            String status = parts[index];
            if (status.isBlank()) {
                continue;
            }
            char letter = status.charAt(0);
            if (isRenameOrCopy(letter)) {
                if (index + 2 >= parts.length) {
                    break;
                }
                String oldPath = parts[++index];
                String newPath = parts[++index];
                files.add(new ProjectGitDtos.CommitFile(newPath, oldPath, String.valueOf(letter), 0, 0, false));
            } else {
                if (index + 1 >= parts.length) {
                    break;
                }
                files.add(new ProjectGitDtos.CommitFile(parts[++index], "", String.valueOf(letter), 0, 0, false));
            }
        }
        return files;
    }

    /**
     * 解析 {@code diff --numstat -z}，把增删行数与二进制标记回填到已有清单上。
     *
     * <p>二进制文件在 numstat 里是 {@code -\t-\tpath}，单独标记，UI 不显示行数。
     */
    static void applyNumstat(String output, List<ProjectGitDtos.CommitFile> files) {
        if (output == null || output.isEmpty() || files == null || files.isEmpty()) {
            return;
        }
        String[] parts = output.split("\u0000", -1);
        Map<String, int[]> stats = new HashMap<>();
        Set<String> binary = new HashSet<>();
        for (int index = 0; index < parts.length; index++) {
            String entry = parts[index];
            if (entry.isBlank()) {
                continue;
            }
            String[] fields = entry.split("\t", 3);
            if (fields.length < 3) {
                continue;
            }
            String path = fields[2];
            if (path.isEmpty() && index + 1 < parts.length) {
                // 重命名在 -z numstat 里路径被拆成 oldPath<NUL>newPath
                path = parts[++index];
            }
            if ("-".equals(fields[0]) || "-".equals(fields[1])) {
                binary.add(path);
                continue;
            }
            try {
                stats.put(path, new int[]{Integer.parseInt(fields[0]), Integer.parseInt(fields[1])});
            } catch (NumberFormatException ignored) {
                // 无法解析时保持 0/0，不影响文件清单本身
            }
        }
        for (int index = 0; index < files.size(); index++) {
            ProjectGitDtos.CommitFile file = files.get(index);
            int[] counts = stats.get(file.path());
            files.set(index, new ProjectGitDtos.CommitFile(file.path(), file.oldPath(), file.status(),
                    counts == null ? 0 : counts[0], counts == null ? 0 : counts[1], binary.contains(file.path())));
        }
    }

    private static String normalizeStatus(char indexStatus, char workTreeStatus) {
        if (indexStatus == '?') {
            return "??";
        }
        if (indexStatus != ' ') {
            return String.valueOf(indexStatus);
        }
        return String.valueOf(workTreeStatus);
    }

    private static boolean isRenameOrCopy(char status) {
        return status == 'R' || status == 'C';
    }
}
