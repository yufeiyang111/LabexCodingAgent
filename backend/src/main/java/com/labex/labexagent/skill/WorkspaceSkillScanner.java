package com.labex.labexagent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 项目级 Skill 扫描器。
 *
 * <p>项目级 Skill 存放在工作区根的 {@code .skill/} 目录下，每个技能一个子目录、入口固定为
 * {@code SKILL.md}：
 *
 * <pre>
 * &lt;workspace&gt;/.skill/review/SKILL.md
 * &lt;workspace&gt;/.skill/review/references/checklist.md
 * &lt;workspace&gt;/.skill/review/scripts/run.sh
 * </pre>
 *
 * <p>这与 user-level Skill 的差别：项目级技能是工作区里的普通文件，附属文件（references/scripts/
 * data）真实存在于磁盘，模型可以直接读取；user-level 技能正文存在数据库里，没有附属文件。
 *
 * <p>扫描不跟随符号链接、跳过噪声目录，且限制深度与数量，避免在大型工作区里失控。
 */
public final class WorkspaceSkillScanner {

    public static final String SKILL_DIR = ".skill";
    public static final String SKILL_FILE = "SKILL.md";

    /** 目录名黑名单：依赖、构建产物、版本控制元数据都不可能是技能。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "node_modules", ".git", "__pycache__", ".venv", "venv", "dist", "build", "target", ".idea", ".vscode");

    private static final int MAX_DEPTH = 4;
    private static final int MAX_SKILLS = 50;
    private static final int MAX_BODY_CHARS = 30_000;

    /** frontmatter 必须位于文件最开头，形如 ---\n...\n---\n正文。 */
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n(.*)$", Pattern.DOTALL);

    private WorkspaceSkillScanner() {
    }

    /** 一个被发现的技能：目录信息足以注入提示词，正文按需再读。 */
    public record Discovered(String skillKey, String title, String description, String relativePath) {
    }

    /**
     * 扫描 {@code <workspaceRoot>/.skill} 下的全部技能。
     *
     * <p>工作区不存在、没有 {@code .skill} 目录、或读取失败时返回空列表 —— 技能是可选增强，
     * 不应让任何一次请求失败。
     */
    public static List<Discovered> scan(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return List.of();
        }
        Path root = workspaceRoot.resolve(SKILL_DIR);
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> SKILL_FILE.equals(path.getFileName().toString()))
                    .filter(path -> !hasExcludedSegment(root, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_SKILLS)
                    .forEach(entries::add);
        } catch (IOException | RuntimeException scanFailure) {
            return List.of();
        }

        List<Discovered> discovered = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            Discovered skill = describe(workspaceRoot, root, entry);
            if (skill != null) {
                discovered.add(skill);
            }
        }
        return List.copyOf(discovered);
    }

    /** 按技能键读取项目级技能正文；不存在返回 null。 */
    public static String readBody(Path workspaceRoot, String relativePath) {
        if (workspaceRoot == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path target = workspaceRoot.resolve(relativePath).normalize();
        if (!target.startsWith(workspaceRoot.normalize()) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            String raw = Files.readString(target, StandardCharsets.UTF_8);
            return limit(body(raw), MAX_BODY_CHARS);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** 列出技能目录下除 SKILL.md 之外的兄弟文件（工作区相对路径），供模型按需读取。 */
    public static List<String> listSiblingFiles(Path workspaceRoot, String skillRelativePath, int limit) {
        if (workspaceRoot == null || skillRelativePath == null || skillRelativePath.isBlank()) {
            return List.of();
        }
        Path skillFile = workspaceRoot.resolve(skillRelativePath).normalize();
        Path dir = skillFile.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, MAX_DEPTH)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(path -> !SKILL_FILE.equals(path.getFileName().toString()))
                    .filter(path -> !hasExcludedSegment(dir, path))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(Math.max(0, limit))
                    .toList();
            for (Path file : files) {
                result.add(toRelative(workspaceRoot, file));
            }
        } catch (IOException | RuntimeException listingFailure) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private static Discovered describe(Path workspaceRoot, Path skillBaseDir, Path skillFile) {
        String raw;
        try {
            raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
        String relativePath = toRelative(workspaceRoot, skillFile);
        String fallbackName = parentName(skillFile, skillBaseDir);
        String fallbackDescription = deriveDescription(raw);

        String title = fallbackName;
        String description = fallbackDescription;
        Matcher matcher = FRONTMATTER.matcher(raw);
        if (matcher.matches()) {
            String frontmatter = matcher.group(1);
            String declared = frontmatterValue(frontmatter, "name");
            String described = frontmatterValue(frontmatter, "description");
            if (!declared.isBlank()) {
                title = declared;
            }
            if (!described.isBlank()) {
                description = described;
            }
        }

        String key = normalizeKey(title);
        if (key.isBlank()) {
            return null;
        }
        return new Discovered(key, limit(title, 120), limit(description, 500), relativePath);
    }

    /** 技能名取 SKILL.md 的父目录名；若 SKILL.md 直接放在 .skill/ 下则由 frontmatter 决定。 */
    private static String parentName(Path skillFile, Path skillBaseDir) {
        Path parent = skillFile.getParent();
        if (parent == null) {
            return "skill";
        }
        String name = parent.getFileName() == null ? "" : parent.getFileName().toString();
        if (name.isBlank()) {
            return "skill";
        }
        String baseName = skillBaseDir.getFileName() == null ? "" : skillBaseDir.getFileName().toString();
        if (name.equals(baseName)) {
            return "skill";
        }
        return name;
    }

    /**
     * frontmatter 缺失 description 时的兜底：取正文第一个非空行去掉 markdown 标记。
     *
     * <p>opencode 会把没有 description 的技能直接过滤掉、永不呈现给模型。这里选择保留并兜底，
     * 因为静默丢弃用户刚放进去的技能更难排查。
     */
    private static String deriveDescription(String raw) {
        String body = body(raw);
        for (String line : body.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("<!--")) {
                continue;
            }
            String plain = trimmed.replaceAll("^#+\\s*", "").replaceAll("[*`_]", "").trim();
            if (!plain.isEmpty()) {
                return limit(plain, 200);
            }
        }
        return "";
    }

    private static String body(String raw) {
        Matcher matcher = FRONTMATTER.matcher(raw == null ? "" : raw);
        return matcher.matches() ? matcher.group(2).strip() : (raw == null ? "" : raw.strip());
    }

    private static String frontmatterValue(String frontmatter, String key) {
        Matcher matcher = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE)
                .matcher(frontmatter);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).replaceAll("^['\"]|['\"]$", "").trim();
    }

    private static boolean hasExcludedSegment(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            if (EXCLUDED_DIRS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String toRelative(Path workspaceRoot, Path file) {
        return workspaceRoot.relativize(file).toString().replace('\\', '/');
    }

    private static String normalizeKey(String value) {
        String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        key = key.replaceAll("^-+", "").replaceAll("-+$", "");
        return key.length() <= 80 ? key : key.substring(0, 80);
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
