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
 * Agent-only workspace scan policy. This policy must not be used for the user-facing file browser.
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

    public static String defaultIgnoreFileContent() {
        StringBuilder content = new StringBuilder();
        content.append("# Agent automatic scan exclusions for this workspace.\n");
        content.append("# Edit this file to add or remove Agent scan paths; it never hides user files.\n");
        content.append("# Agent never automatically reads files larger than 20 MiB.\n\n");
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

        private ScanIgnoreRules(Path root, List<String> lines) {
            this.root = root;
            this.patterns = parse(lines);
        }

        public boolean shouldSkipDirectory(Path directory) {
            if (root.equals(directory) || ProjectScanPolicy.shouldSkipDirectory(root, directory)) {
                return !root.equals(directory) && ProjectScanPolicy.shouldSkipDirectory(root, directory);
            }
            return matches(directory);
        }

        public boolean shouldSkipFile(Path file) {
            return isIgnoredRelativePath(relativePath(file)) || matches(file);
        }

        public boolean shouldSkipEntry(Path entry, boolean directory) {
            return directory ? shouldSkipDirectory(entry) : shouldSkipFile(entry);
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

    private record IgnorePattern(String expression, boolean directoryOnly) {
        boolean matches(String relativePath) {
            if (relativePath == null || relativePath.isBlank()) {
                return false;
            }
            if (directoryOnly && (relativePath.equals(expression) || relativePath.startsWith(expression + "/"))) {
                return true;
            }
            String regex = globToRegex(expression);
            if (Pattern.matches(regex, relativePath)) {
                return true;
            }
            return !expression.contains("/") && Pattern.matches(regex, relativePath.substring(relativePath.lastIndexOf('/') + 1));
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
