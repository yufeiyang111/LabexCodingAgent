package com.labex.labexagent.tool.impl;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import com.labex.service.AgentSkillService;
import org.springframework.stereotype.Component;

@Component
public class SkillTool
implements AgentTool {
    private final AgentSkillService skillService;

    public SkillTool(AgentSkillService skillService) {
        this.skillService = skillService;
    }

    public ToolDefinition definition() {
        return ToolDefinition.builder().name("skill")
                .description("List enabled user-level skill metadata when name is omitted. Provide a key or topic to load only one skill's detailed guidance.")
                .stringProperty("name", "skill key or topic", false)
                .build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String name = ToolSupport.stringArg((JsonObject) args, "name", "");
        if (name.isBlank()) {
            return ToolResult.ok(renderCatalog(skillService.listEnabledCatalog(context.getStudentId())));
        }
        var skill = skillService.findEnabled(context.getStudentId(), name);
        if (skill == null) {
            return ToolResult.failed("Skill not found or disabled: " + name);
        }
        return ToolResult.ok("# " + skill.getTitle() + "\n\nkey: " + skill.getSkillKey()
                + "\n\n" + (skill.getDescription() == null ? "" : skill.getDescription() + "\n\n")
                + skill.getContent());
    }

    private String renderCatalog(java.util.List<AgentSkillService.SkillCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return "No enabled user-level skills.";
        }
        StringBuilder result = new StringBuilder("Available enabled user-level skills:\n");
        for (AgentSkillService.SkillCatalogEntry entry : catalog) {
            result.append("- ").append(blankToFallback(entry.skillKey(), "(unnamed)"));
            if (entry.title() != null && !entry.title().isBlank()) {
                result.append(": ").append(entry.title().trim());
            }
            if (entry.description() != null && !entry.description().isBlank()) {
                result.append(" — ").append(entry.description().trim());
            }
            result.append("\n");
        }
        return result.toString().trim();
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
