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
        return ToolDefinition.builder().name("skill").description("Read one enabled user-level skill by key or topic. Use it when the task needs reusable user guidance.").stringProperty("name", "skill key or topic", false).build();
    }

    public ToolResult execute(AgentContext context, JsonObject args) {
        String name = ToolSupport.stringArg((JsonObject)args, "name", "");
        if (name.isBlank()) {
            String contextText = skillService.buildPromptContext(context.getStudentId());
            return ToolResult.ok(contextText.isBlank() ? "No enabled user-level skills." : contextText);
        }
        var skill = skillService.findEnabled(context.getStudentId(), name);
        if (skill == null) {
            return ToolResult.failed("Skill not found or disabled: " + name);
        }
        return ToolResult.ok("# " + skill.getTitle() + "\n\nkey: " + skill.getSkillKey()
                + "\n\n" + (skill.getDescription() == null ? "" : skill.getDescription() + "\n\n")
                + skill.getContent());
    }
}
