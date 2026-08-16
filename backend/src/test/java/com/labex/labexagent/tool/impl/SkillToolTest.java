package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.entity.AgentSkill;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.service.AgentSkillService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillToolTest {

    @Test
    void blankNameReturnsOnlyTheEnabledSkillCatalogWithoutLoadingSkillBodies() {
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.listEnabledCatalog(7)).thenReturn(List.of(
                new AgentSkillService.SkillCatalogEntry("frontend-review", "前端审查", "检查 Vue 页面")));

        ToolResult result = new SkillTool(skills).execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("frontend-review").contains("前端审查").contains("检查 Vue 页面");
        verify(skills, never()).buildPromptContext(anyInt());
        verify(skills, never()).findEnabled(anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void namedSkillReadsOnlyTheRequestedEnabledSkillBody() {
        AgentSkillService skills = mock(AgentSkillService.class);
        AgentSkill skill = new AgentSkill();
        skill.setSkillKey("frontend-review");
        skill.setTitle("前端审查");
        skill.setDescription("检查 Vue 页面");
        skill.setContent("# 仅本 Skill 的详细规则");
        when(skills.findEnabled(7, "frontend-review")).thenReturn(skill);
        JsonObject input = new JsonObject();
        input.addProperty("name", "frontend-review");

        ToolResult result = new SkillTool(skills).execute(context(), input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("# 仅本 Skill 的详细规则");
        verify(skills, never()).buildPromptContext(anyInt());
    }

    private static AgentContext context() {
        return new AgentContext("session-7", 7, null, "conversation-7", 7L,
                Path.of("."), new ArrayList<>(), 0);
    }
}
