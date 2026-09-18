package com.labex.labexagent.service;

import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工作区排除规则的唯一实现：解析 .labex-agentignore、内置目录硬底与默认规则内容。
 *
 * <p>使用边界：Agent 侧扫描/索引（WorkspaceScanner、Glob/Grep/ListFiles 等工具）与
 * 项目导出/下载（com.labex.labexagent.workspace.ExportSelectionPolicy）共用本类的解析与 glob 实现；
 * 用户侧文件浏览器（listProjectTree*）不得使用，它只按 SecureWorkspacePath 独立判定。
 * 任何"工作区里有什么"的新判定都必须复用这里，禁止再写第二套 ignore 解析。
 */
public final class ProjectScanPolicy {
    public static final String AGENT_IGNORE_FILE = ".labex-agentignore";
    public static final long MAX_AGENT_READ_FILE_BYTES = 20L * 1024 * 1024;
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".hg", ".svn",
            ".labex", ".labex-agent", ".labexagent",
            "appdata", "microsoft", "npm-cache", ".npm", ".cache",
            "node_modules", "bower_components", "vendor",
            "dist", "build", "target", "out", "coverage",
            ".next", ".nuxt", ".svelte-kit", ".vite",
            "__pycache__", ".pytest_cache", ".mypy_cache",
            ".idea", ".vscode", ".gradle", ".mvn");

    private ProjectScanPolicy() {
    }

    public static ScanIgnoreRules loadIgnoreRules(SecureWorkspacePath paths) {
        Path root = paths.workspaceRoot();
        Path ignoreFile = root.resolve(AGENT_IGNORE_FILE);
        if (!Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS) || !isSafeWorkspaceEntry(paths, ignoreFile)) {
            return new ScanIgnoreRules(root, defaultIgnoreFileLines());
        }
        try {
            return new ScanIgnoreRules(root, Files.readAllLines(ignoreFile, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return new ScanIgnoreRules(root, List.of());
        }
    }

    /**
     * 只加载 .labex-agentignore 原文声明的规则：不回落默认列表，也不叠加内置目录硬底。
     *
     * <p>供"以用户声明为准"的场景使用（项目导出/下载排除）；Agent 扫描请继续用
     * {@link #loadIgnoreRules}。文件缺失、非普通文件（目录/符号链接）、越出工作区、
     * 读取失败（含非 UTF-8 字节）时一律返回空规则，调用方据此退化为"无文件级排除"。
     */
    public static ScanIgnoreRules loadDeclaredIgnoreRules(SecureWorkspacePath paths) {
        Path root = paths.workspaceRoot();
        Path ignoreFile = root.resolve(AGENT_IGNORE_FILE);
        if (!Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS) || !isSafeWorkspaceEntry(paths, ignoreFile)) {
            return new ScanIgnoreRules(root, List.of(), true);
        }
        try {
            return new ScanIgnoreRules(root, Files.readAllLines(ignoreFile, StandardCharsets.UTF_8), true);
        } catch (IOException ignored) {
            return new ScanIgnoreRules(root, List.of(), true);
        }
    }

    public static String defaultIgnoreFileContent() {
        StringBuilder content = new StringBuilder();
        content.append("# Agent automatic scan exclusions for this workspace.\n");
        content.append("# Entries affect Agent scanning and project export; the file browser always shows them.\n");
        content.append("# Agent never automatically reads files larger than 20 MiB.\n");
        content.append("# Entries declared here are never included in project export/download packages.\n\n");
        for (String directory : ignoredDirectoryNames()) {
            content.append(directory).append("/\n");
        }
        return content.toString();
    }

    private static List<String> defaultIgnoreFileLines() {
        return defaultIgnoreFileContent().lines().toList();
    }

    public static boolean shouldSkipDirectory(Path workspaceRoot, Path directory) {
        if (workspaceRoot.equals(directory)) {
            return false;
        }
        Path name = directory.getFileName();
        return name != null && isIgnoredDirectoryName(name.toString());
    }

    public static boolean isIgnoredDirectoryName(String name) {
        return name != null && IGNORED_DIRECTORY_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isInternalRuntimeEntry(Path workspaceRoot, Path entry) {
        if (workspaceRoot == null || entry == null) {
            return false;
        }
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path normalized = entry.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(normalized);
        return relative.getNameCount() >= 2
                && ".labex-agent".equalsIgnoreCase(relative.getName(0).toString())
                && "runtime".equalsIgnoreCase(relative.getName(1).toString());
    }

    public static boolean isIgnoredRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        for (Path segment : Path.of(relativePath.replace('\\', '/'))) {
            if (isIgnoredDirectoryName(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    public static List<String> ignoredDirectoryNames() {
        return IGNORED_DIRECTORY_NAMES.stream().sorted().toList();
    }

    public static boolean isSafeWorkspaceEntry(SecureWorkspacePath paths, Path entry) {
        try {
            Path normalized = entry.toAbsolutePath().normalize();
            if (!normalized.startsWith(paths.workspaceRoot())) {
                return false;
            }
            String relative = paths.workspaceRoot().relativize(normalized).toString();
            paths.resolveExisting(relative.isBlank() ? "." : relative);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static final class ScanIgnoreRules {
        private final Path root;
        private final List<IgnorePattern> patterns;
        /** true = 只用文件里声明的规则，不叠加内置忽略目录硬底（导出/下载场景）。 */
        private final boolean declaredOnly;

        private ScanIgnoreRules(Path root, List<String> lines) {
            this(root, lines, false);
        }

        private ScanIgnoreRules(Path root, List<String> lines, boolean declaredOnly) {
            this.root = root;
            this.patterns = parse(lines);
            this.declaredOnly = declaredOnly;
        }

        public boolean shouldSkipDirectory(Path directory) {
            if (root.equals(directory)) {
                return false;
            }
            if (!declaredOnly && ProjectScanPolicy.shouldSkipDirectory(root, directory)) {
                return true;
            }
            return matches(directory);
        }

        public boolean shouldSkipFile(Path file) {
            return (!declaredOnly && isIgnoredRelativePath(relativePath(file))) || matches(file);
        }

        public boolean shouldSkipEntry(Path entry, boolean directory) {
            return directory ? shouldSkipDirectory(entry) : shouldSkipFile(entry);
        }

        /** 是否解析出了至少一条有效规则（用于诊断"文件缺失"与"文件为空"）。 */
        public boolean hasPatterns() {
            return !patterns.isEmpty();
        }

        private boolean matches(Path entry) {
            String relative = relativePath(entry);
            return patterns.stream().anyMatch(pattern -> pattern.matches(relative));
        }

        private String relativePath(Path entry) {
            return root.relativize(entry.toAbsolutePath().normalize()).toString().replace('\\', '/');
        }

        private List<IgnorePattern> parse(List<String> lines) {
            List<IgnorePattern> result = new ArrayList<>();
            if (lines == null) {
                return result;
            }
            for (String line : lines) {
                String value = line == null ? "" : line.trim().replace('\\', '/');
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                while (value.startsWith("/")) {
                    value = value.substring(1);
                }
                boolean directoryOnly = value.endsWith("/");
                if (directoryOnly) {
                    value = value.substring(0, value.length() - 1);
                }
                if (value.isBlank() || value.startsWith("!")) {
                    continue;
                }
                result.add(new IgnorePattern(value, directoryOnly));
            }
            return List.copyOf(result);
        }
    }

    private record IgnorePattern(String expression, boolean directoryOnly, Pattern compiled) {
        IgnorePattern(String expression, boolean directoryOnly) {
            this(expression, directoryOnly, Pattern.compile(globToRegex(expression)));
        }

        boolean matches(String relativePath) {
            if (relativePath == null || relativePath.isBlank()) {
                return false;
            }
            if (directoryOnly && (relativePath.equals(expression) || relativePath.startsWith(expression + "/"))) {
                return true;
            }
            if (compiled.matcher(relativePath).matches()) {
                return true;
            }
            return !expression.contains("/")
                    && compiled.matcher(relativePath.substring(relativePath.lastIndexOf('/') + 1)).matches();
        }

        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int index = 0; index < glob.length(); index++) {
                char character = glob.charAt(index);
                if (character == '*') {
                    boolean globStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
                    if (globStar) {
                        index++;
                        if (index + 1 < glob.length() && glob.charAt(index + 1) == '/') {
                            regex.append("(?:.*/)?");
                            index++;
                        } else {
                            regex.append(".*");
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (character == '?') {
                    regex.append("[^/]");
                } else {
                    if ("\\.[]()^$+|{}".indexOf(character) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(character);
                }
            }
            return regex.append('$').toString();
        }
    }
}
