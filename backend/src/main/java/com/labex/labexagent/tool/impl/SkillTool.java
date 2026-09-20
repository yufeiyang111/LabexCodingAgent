package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.AgentSkillService;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 技能加载工具。技能有两个来源：
 *
 * <ul>
 *   <li><b>项目级</b>：工作区 {@code .skill/<name>/SKILL.md}，附属文件（references/scripts/data）
 *       真实存在，模型可以直接读取；</li>
 *   <li><b>用户级</b>：账号维度配置、跨项目复用，正文存在数据库里。</li>
 * </ul>
 *
 * <p>同名时项目级覆盖用户级。
 */
@Component
public class SkillTool
implements AgentTool {
    private final AgentSkillService skillService;

    public SkillTool(AgentSkillService skillService) {
        this.skillService = skillService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("skill")
                .description("List available skills when name is omitted. Provide a name to load that skill's "
                        + "guidance. Project skills live under .skill/ in the workspace and may ship extra "
                        + "reference files you can read afterwards.")
                .stringProperty("name", "skill key from the available skills list", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        Path workspaceRoot = context.getWorkspaceRoot();
        Integer studentId = context.getStudentId();
        String name = ToolSupport.stringArg(args, "name", "");

        if (name.isBlank()) {
            return ToolResult.ok(renderCatalog(skillService.resolveCatalog(studentId, workspaceRoot)));
        }

        AgentSkillService.LoadedSkill skill = skillService.load(studentId, workspaceRoot, name);
        if (skill == null) {
            return ToolResult.failed("Skill not found or disabled: " + name);
        }
        return ToolResult.ok(renderSkill(skill));
    }

    private String renderCatalog(List<AgentSkillService.CatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return "No skills are available. Project skills can be added under .skill/<name>/SKILL.md.";
        }
        StringBuilder result = new StringBuilder("Available skills:\n");
        for (AgentSkillService.CatalogEntry entry : catalog) {
            result.append("- ").append(blankToFallback(entry.skillKey(), "(unnamed)"));
            if (entry.title() != null && !entry.title().isBlank()) {
                result.append(": ").append(entry.title().trim());
            }
            if (entry.description() != null && !entry.description().isBlank()) {
                result.append(" — ").append(entry.description().trim());
            }
            result.append(" [").append(entry.source()).append("]");
            result.append("\n");
        }
        return result.toString().trim();
    }

    private String renderSkill(AgentSkillService.LoadedSkill skill) {
        StringBuilder result = new StringBuilder();
        result.append("<skill_content name=\"").append(skill.skillKey()).append("\" source=\"")
                .append(skill.source()).append("\">\n");
        result.append("# ").append(skill.title()).append("\n\n");
        result.append(skill.content());
        if (skill.relativePath() != null && !skill.relativePath().isBlank()) {
            result.append("\n\nSkill file: ").append(skill.relativePath());
            result.append("\nRelative paths in this skill resolve against its directory.");
        }
        if (skill.files() != null && !skill.files().isEmpty()) {
            result.append("\n\n<skill_files>\n");
            for (String file : skill.files()) {
                result.append("<file>").append(file).append("</file>\n");
            }
            result.append("</skill_files>\nNote: file list is sampled.");
        }
        result.append("\n</skill_content>");
        return result.toString();
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
