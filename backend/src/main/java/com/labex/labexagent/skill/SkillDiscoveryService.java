package com.labex.labexagent.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 发现服务
 * 扫描目录中的 SKILL.md 文件，解析 YAML frontmatter
 * 支持多源发现：项目目录、用户上传、全局配置
 */
@Slf4j
@Service
public class SkillDiscoveryService {

    // YAML frontmatter 正则: ---\n...\n---
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
        "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL
    );

    // YAML 简单键值对解析
    private static final Pattern YAML_KV_PATTERN = Pattern.compile(
        "^\\s*([\\w_-]+)\\s*:\\s*(.+?)\\s*$"
    );

    // 缓存: studentId -> skillName -> SkillInfo
    private final Map<Integer, Map<String, SkillInfo>> skillCache = new ConcurrentHashMap<>();

    /**
     * Skill 信息
     */
    public static class SkillInfo {
        private final String name;
        private final String description;
        private final String content;
        private final String location;
        private final List<String> files;

        public SkillInfo(String name, String description, String content, String location, List<String> files) {
            this.name = name;
            this.description = description;
            this.content = content;
            this.location = location;
            this.files = files;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getContent() { return content; }
        public String getLocation() { return location; }
        public List<String> getFiles() { return files; }
    }

    /**
     * 扫描目录中的所有 SKILL.md 文件
     */
    public List<SkillInfo> scanDirectory(Path directory) {
        List<SkillInfo> skills = new ArrayList<>();
        if (directory == null || !Files.exists(directory)) {
            return skills;
        }

        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equalsIgnoreCase("SKILL.md")) {
                        try {
                            SkillInfo skill = parseSkillFile(file, directory);
                            if (skill != null) {
                                skills.add(skill);
                            }
                        } catch (IOException e) {
                            log.warn("Failed to parse skill file: {}", file, e);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to visit file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan directory: {}", directory, e);
        }

        return skills;
    }

    /**
     * 解析单个 SKILL.md 文件
     */
    public SkillInfo parseSkillFile(Path file, Path baseDir) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        return parseSkillContent(raw, file.toString(), baseDir);
    }

    /**
     * 解析 Skill 内容
     */
    public SkillInfo parseSkillContent(String raw, String location, Path baseDir) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            // 没有 frontmatter，使用文件名作为 name
            String fileName = Paths.get(location).getFileName().toString();
            String name = fileName.replace(".md", "").toLowerCase()
                .replaceAll("[^a-z0-9-]", "-");
            return new SkillInfo(name, null, raw, location, listNearbyFiles(baseDir, Paths.get(location)));
        }

        String frontmatter = matcher.group(1);
        String content = matcher.group(2);

        // 解析 frontmatter
        String name = null;
        String description = null;
        for (String line : frontmatter.split("\\n")) {
            Matcher kv = YAML_KV_PATTERN.matcher(line);
            if (kv.matches()) {
                String key = kv.group(1).trim();
                String value = kv.group(2).trim();
                // 移除引号
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                switch (key) {
                    case "name" -> name = value;
                    case "description" -> description = value;
                }
            }
        }

        if (name == null || name.isBlank()) {
            String fileName = Paths.get(location).getFileName().toString();
            name = fileName.replace(".md", "").toLowerCase()
                .replaceAll("[^a-z0-9-]", "-");
        }

        List<String> files = listNearbyFiles(baseDir, Paths.get(location));
        return new SkillInfo(name, description, content, location, files);
    }

    /**
     * 列出 Skill 目录下的相关文件（排除 SKILL.md 本身）
     */
    private List<String> listNearbyFiles(Path baseDir, Path skillFile) {
        List<String> files = new ArrayList<>();
        if (baseDir == null) return files;

        Path skillDir = skillFile.getParent();
        if (skillDir == null) return files;

        try {
            Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.equalsIgnoreCase("SKILL.md") && !fileName.startsWith(".")) {
                        files.add(file.toAbsolutePath().toString());
                    }
                    if (files.size() >= 10) return FileVisitResult.TERMINATE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Failed to list nearby files: {}", e.getMessage());
        }
        return files;
    }

    /**
     * 注册学生上传的 Skill 目录
     */
    public void registerSkills(Integer studentId, Path directory) {
        List<SkillInfo> skills = scanDirectory(directory);
        Map<String, SkillInfo> map = skillCache.computeIfAbsent(studentId, k -> new ConcurrentHashMap<>());
        for (SkillInfo skill : skills) {
            map.put(skill.getName(), skill);
            log.info("Registered skill '{}' for student {}", skill.getName(), studentId);
        }
    }

    /**
     * 获取学生的所有 Skill
     */
    public List<SkillInfo> getStudentSkills(Integer studentId) {
        Map<String, SkillInfo> map = skillCache.get(studentId);
        if (map == null) return Collections.emptyList();
        return new ArrayList<>(map.values());
    }

    /**
     * 按名称查找 Skill
     */
    public SkillInfo findSkill(Integer studentId, String name) {
        Map<String, SkillInfo> map = skillCache.get(studentId);
        if (map == null) return null;
        return map.get(name);
    }

    /**
     * 模糊搜索 Skill（按名称或描述）
     */
    public List<SkillInfo> searchSkills(Integer studentId, String query) {
        Map<String, SkillInfo> map = skillCache.get(studentId);
        if (map == null) return Collections.emptyList();

        String lowerQuery = query.toLowerCase();
        List<SkillInfo> results = new ArrayList<>();
        for (SkillInfo skill : map.values()) {
            if (skill.getName().toLowerCase().contains(lowerQuery) ||
                (skill.getDescription() != null && skill.getDescription().toLowerCase().contains(lowerQuery))) {
                results.add(skill);
            }
        }
        return results;
    }

    /**
     * 构建所有 Skill 的上下文提示词
     */
    public String buildPromptContext(Integer studentId) {
        List<SkillInfo> skills = getStudentSkills(studentId);
        if (skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        sb.append("The following skills are available. Use the `skill` tool to load a specific skill by name.\n\n");
        for (SkillInfo skill : skills) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null) {
                sb.append(": ").append(skill.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 清除学生的 Skill 缓存
     */
    public void clearCache(Integer studentId) {
        skillCache.remove(studentId);
    }
}
