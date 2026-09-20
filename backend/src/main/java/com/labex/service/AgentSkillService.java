package com.labex.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.labex.entity.AgentSkill;
import com.labex.labexagent.skill.WorkspaceSkillScanner;
import com.labex.mapper.AgentSkillMapper;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AgentSkillService extends ServiceImpl<AgentSkillMapper, AgentSkill> {
    private static final int MAX_SKILL_CONTENT = 30000;
    /** 注入提示词的技能条数上限；超出会让目录本身变成噪声。 */
    private static final int MAX_CATALOG_ENTRIES = 12;
    private static final int MAX_SIBLING_FILES = 20;

    public List<AgentSkill> listByStudent(Integer studentId) {
        return this.list(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getStudentId, studentId)
                .eq(AgentSkill::getStatus, 1)
                .orderByDesc(AgentSkill::getIsEnabled)
                .orderByDesc(AgentSkill::getUpdateTime));
    }

    /**
     * 仅查询是否存在可供 native runtime 按需读取的 Skill，避免在 Provider 请求前加载正文。
     */
    public boolean hasEnabledSkills(Integer studentId) {
        if (studentId == null) {
            return false;
        }
        return this.count(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getStudentId, studentId)
                .eq(AgentSkill::getStatus, 1)
                .eq(AgentSkill::getIsEnabled, 1)) > 0;
    }

    /**
     * 合并两个来源的技能目录：项目级（工作区 {@code .skill/}）与用户级（数据库）。
     *
     * <p>项目级优先：同名技能以工作区里的那份为准，用户因此可以直接在项目里覆盖全局技能，
     * 而不必去改全局配置。目录里只有键/标题/描述这类紧凑信息，正文要另外按名加载 —— 这样可以
     * 把提示词开销压到与技能数量无关的量级。
     */
    public List<CatalogEntry> resolveCatalog(Integer studentId, Path workspaceRoot) {
        return mergeCatalog(WorkspaceSkillScanner.scan(workspaceRoot), listEnabledAll(studentId));
    }

    /**
     * 合并两层技能目录的纯函数。抽出来是为了让"项目级覆盖用户级"这条规则可以脱离数据库被直接断言。
     *
     * <p>顺序：先项目级（更具体），再用户级；用户级里与项目级同名的条目被丢弃。总数超出上限时
     * 截断，防止目录本身变成噪声。
     */
    public static List<CatalogEntry> mergeCatalog(List<WorkspaceSkillScanner.Discovered> projectSkills,
                                                  List<AgentSkill> userSkills) {
        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (WorkspaceSkillScanner.Discovered skill : projectSkills == null
                ? List.<WorkspaceSkillScanner.Discovered>of() : projectSkills) {
            if (seen.add(skill.skillKey())) {
                entries.add(new CatalogEntry(skill.skillKey(), skill.title(), skill.description(),
                        SOURCE_PROJECT, skill.relativePath()));
            }
        }
        for (AgentSkill skill : userSkills == null ? List.<AgentSkill>of() : userSkills) {
            String key = normalizeKey(skill.getSkillKey());
            if (key.isBlank() || !seen.add(key)) {
                continue;
            }
            entries.add(new CatalogEntry(key, skill.getTitle(), skill.getDescription(), SOURCE_USER, null));
        }

        return entries.size() <= MAX_CATALOG_ENTRIES
                ? List.copyOf(entries)
                : List.copyOf(entries.subList(0, MAX_CATALOG_ENTRIES));
    }

    /** 按名加载技能正文。项目级优先；两个来源都没有则返回 null。 */
    public LoadedSkill load(Integer studentId, Path workspaceRoot, String name) {
        String wanted = name == null ? "" : name.trim();
        String normalized = normalizeKey(wanted);
        if (normalized.isBlank()) {
            return null;
        }

        for (WorkspaceSkillScanner.Discovered skill : WorkspaceSkillScanner.scan(workspaceRoot)) {
            boolean matches = skill.skillKey().equals(normalized)
                    || skill.title().equalsIgnoreCase(wanted);
            if (!matches) {
                continue;
            }
            String content = WorkspaceSkillScanner.readBody(workspaceRoot, skill.relativePath());
            if (content == null) {
                return null;
            }
            return new LoadedSkill(skill.skillKey(), skill.title(), SOURCE_PROJECT, skill.relativePath(), content,
                    WorkspaceSkillScanner.listSiblingFiles(workspaceRoot, skill.relativePath(), MAX_SIBLING_FILES));
        }

        AgentSkill skill = findEnabled(studentId, wanted);
        if (skill == null) {
            return null;
        }
        return new LoadedSkill(normalizeKey(skill.getSkillKey()), skill.getTitle(), SOURCE_USER, null,
                skill.getContent() == null ? "" : skill.getContent(), List.of());
    }

    /**
     * 是否存在任何可发现的技能。项目级技能是工作区里的普通文件，用户随时可以放进去，
     * 因此工具可用性判定必须同时看两层，否则刚放进 {@code .skill/} 的技能会因为账号没有
     * 用户级技能而整个不可见。
     */
    public boolean hasAnySkill(Integer studentId, Path workspaceRoot) {
        if (!WorkspaceSkillScanner.scan(workspaceRoot).isEmpty()) {
            return true;
        }
        return hasEnabledSkills(studentId);
    }

    /**
     * 渲染可注入提示词的紧凑技能目录。
     *
     * <p>纯函数，便于直接断言输出；只暴露模型"决定要不要加载"所需的最小信息（名字、何时使用、
     * 去哪儿读），不包含正文。
     */
    public static String renderCatalogBlock(List<CatalogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("<available_skills>\n");
        for (CatalogEntry entry : entries) {
            builder.append("  <skill>\n");
            builder.append("    <name>").append(escape(entry.skillKey())).append("</name>\n");
            if (entry.description() != null && !entry.description().isBlank()) {
                builder.append("    <description>").append(escape(entry.description())).append("</description>\n");
            }
            builder.append("    <source>").append(escape(entry.source())).append("</source>\n");
            if (entry.relativePath() != null && !entry.relativePath().isBlank()) {
                builder.append("    <location>").append(escape(entry.relativePath())).append("</location>\n");
            }
            builder.append("  </skill>\n");
        }
        builder.append("</available_skills>");
        return builder.toString();
    }

    /** 技能来自工作区的 {@code .skill/} 目录，附属文件真实存在、模型可直接读取。 */
    public static final String SOURCE_PROJECT = "project";
    /** 技能存在数据库里，跨项目复用；没有附属文件。 */
    public static final String SOURCE_USER = "user";

    public record CatalogEntry(String skillKey, String title, String description, String source, String relativePath) {
    }

    public record LoadedSkill(String skillKey, String title, String source, String relativePath,
                              String content, List<String> files) {
    }

    private List<AgentSkill> listEnabledAll(Integer studentId) {
        if (studentId == null) {
            return List.of();
        }
        return this.list(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getStudentId, studentId)
                .eq(AgentSkill::getStatus, 1)
                .eq(AgentSkill::getIsEnabled, 1)
                .orderByDesc(AgentSkill::getUpdateTime));
    }

    public AgentSkill getOwned(Integer studentId, Integer skillId) {
        return this.lambdaQuery()
                .eq(AgentSkill::getStudentId, studentId)
                .eq(AgentSkill::getSkillId, skillId)
                .eq(AgentSkill::getStatus, 1)
                .one();
    }

    public AgentSkill findEnabled(Integer studentId, String name) {
        String normalized = normalizeKey(name);
        if (normalized.isBlank()) {
            return null;
        }
        return this.lambdaQuery()
                .eq(AgentSkill::getStudentId, studentId)
                .eq(AgentSkill::getStatus, 1)
                .eq(AgentSkill::getIsEnabled, 1)
                .and(q -> q.eq(AgentSkill::getSkillKey, normalized).or().like(AgentSkill::getTitle, name))
                .last("LIMIT 1")
                .one();
    }

    public AgentSkill create(Integer studentId, Map<String, Object> body) {
        // 先检查是否已存在相同 student_id 和 skill_key 的记录（包括已删除的）
        String key = string(body, "skillKey", "");
        String title = string(body, "title", "");
        if (key.isBlank()) {
            key = title;
        }
        key = normalizeKey(key);

        // 查询是否已存在（不过滤 status，因为唯一约束是 student_id + skill_key）
        AgentSkill existing = this.lambdaQuery()
                .eq(AgentSkill::getStudentId, studentId)
                .eq(AgentSkill::getSkillKey, key)
                .one();

        if (existing != null) {
            // 如果存在（无论是否已删除），则更新并恢复状态
            applyBody(existing, studentId, body, false);
            existing.setStatus(1);  // 恢复状态
            this.updateById(existing);
            return existing;
        } else {
            // 如果不存在，则插入新记录
            AgentSkill skill = new AgentSkill();
            applyBody(skill, studentId, body, true);
            this.save(skill);
            return skill;
        }
    }

    public AgentSkill update(Integer studentId, Integer skillId, Map<String, Object> body) {
        AgentSkill skill = getOwned(studentId, skillId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found");
        }
        applyBody(skill, studentId, body, false);
        this.updateById(skill);
        return skill;
    }

    public void delete(Integer studentId, Integer skillId) {
        AgentSkill skill = getOwned(studentId, skillId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found");
        }
        skill.setStatus(0);
        skill.setUpdateTime(LocalDateTime.now());
        this.updateById(skill);
    }

    private void applyBody(AgentSkill skill, Integer studentId, Map<String, Object> body, boolean creating) {
        if (creating) {
            skill.setStudentId(studentId);
            skill.setStatus(1);
            skill.setCreateTime(LocalDateTime.now());
        }
        String key = string(body, "skillKey", creating ? "" : skill.getSkillKey());
        String title = string(body, "title", creating ? "" : skill.getTitle());
        String content = string(body, "content", creating ? "" : skill.getContent());
        String description = string(body, "description", skill.getDescription());
        Integer enabled = bool(body, "isEnabled", skill.getIsEnabled() == null ? 1 : skill.getIsEnabled());

        if (key.isBlank()) {
            key = title;
        }
        key = normalizeKey(key);
        if (key.isBlank()) {
            throw new IllegalArgumentException("Skill key is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Skill title is required");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Skill content is required");
        }
        if (content.length() > MAX_SKILL_CONTENT) {
            throw new IllegalArgumentException("Skill content is too long");
        }

        skill.setSkillKey(key);
        skill.setTitle(limit(title.trim(), 120));
        skill.setDescription(description == null ? "" : limit(description.trim(), 500));
        skill.setContent(content.trim());
        skill.setIsEnabled(enabled);
        skill.setUpdateTime(LocalDateTime.now());
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        String key = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        key = key.replaceAll("^-+", "").replaceAll("-+$", "");
        return key.length() <= 80 ? key : key.substring(0, 80);
    }

    private String string(Map<String, Object> body, String key, String fallback) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return fallback == null ? "" : fallback;
        }
        return String.valueOf(body.get(key)).trim();
    }

    private Integer bool(Map<String, Object> body, String key, Integer fallback) {
        if (body == null || !body.containsKey(key) || body.get(key) == null) {
            return fallback == null ? 1 : fallback;
        }
        Object value = body.get(key);
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String raw = String.valueOf(value).trim();
        return "1".equals(raw) || "true".equalsIgnoreCase(raw) ? 1 : 0;
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
