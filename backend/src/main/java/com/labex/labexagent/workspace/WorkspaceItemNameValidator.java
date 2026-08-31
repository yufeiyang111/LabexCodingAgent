package com.labex.labexagent.workspace;

/**
 * 工作区条目名称校验的唯一实现。
 * 原 StudentProjectServiceImpl#validateItemName 抽取而来，供创建、重命名、
 * 上传、复制目标等所有写入入口复用；禁止各处自行拼正则。
 */
public final class WorkspaceItemNameValidator {

    private WorkspaceItemNameValidator() {
    }

    /** 校验单个文件/文件夹名，返回 trim 后的安全名称；非法时抛出 IllegalArgumentException。 */
    public static String validate(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmed = name.trim();
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            throw new IllegalArgumentException("Invalid name");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.matches(".*[<>:\"|?*].*")) {
            throw new IllegalArgumentException("Name contains unsupported characters");
        }
        return trimmed;
    }

    /** 校验多级相对路径的每个段名（如上传保留目录结构时的 webkitRelativePath）。 */
    public static String validateRelativeSegments(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        String[] segments = relativePath.trim().replace('\\', '/').split("/");
        StringBuilder rebuilt = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            String safeSegment = validate(segment);
            if (rebuilt.length() > 0) {
                rebuilt.append('/');
            }
            rebuilt.append(safeSegment);
        }
        if (rebuilt.length() == 0) {
            throw new IllegalArgumentException("Path is required");
        }
        return rebuilt.toString();
    }
}
