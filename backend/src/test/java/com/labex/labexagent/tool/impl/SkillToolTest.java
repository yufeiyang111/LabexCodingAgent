package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.service.AgentSkillService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * skill 工具的行为测试。
 *
 * <p>锁定的核心不变量：列目录时不加载任何技能正文（否则每轮请求都会被技能正文撑爆），
 * 以及按名加载时项目级优先。
 */
class SkillToolTest {

    private static final Path WORKSPACE = Path.of("/srv/labex-agent/workspaces/project-7");

    @Test
    @DisplayName("不传 name 时只列目录，绝不加载任何技能正文")
    void blankNameReturnsOnlyTheCatalogWithoutLoadingBodies() {
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.resolveCatalog(eq(7), any())).thenReturn(List.of(
                new AgentSkillService.CatalogEntry("frontend-review", "前端审查", "检查 Vue 页面",
                        AgentSkillService.SOURCE_USER, null)));

        ToolResult result = new SkillTool(skills).execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent())
                .contains("frontend-review")
                .contains("前端审查")
                .contains("检查 Vue 页面")
                .contains("[user]");
        verify(skills, never()).load(anyInt(), any(), any());
    }

    @Test
    @DisplayName("传 name 时加载该技能正文，并标注来源")
    void namedSkillLoadsOnlyTheRequestedBody() {
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.load(eq(7), any(), eq("frontend-review"))).thenReturn(
                new AgentSkillService.LoadedSkill("frontend-review", "前端审查", AgentSkillService.SOURCE_USER,
                        null, "# 仅本 Skill 的详细规则", List.of()));

        JsonObject input = new JsonObject();
        input.addProperty("name", "frontend-review");

        ToolResult result = new SkillTool(skills).execute(context(), input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent())
                .contains("# 仅本 Skill 的详细规则")
                .contains("source=\"user\"");
        verify(skills, never()).resolveCatalog(anyInt(), any());
    }

    @Test
    @DisplayName("项目级技能返回基准文件与兄弟文件，模型据此按需读取 references/scripts")
    void projectSkillExposesBaseFileAndSiblingFiles() {
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.load(eq(7), any(), eq("review"))).thenReturn(
                new AgentSkillService.LoadedSkill("review", "review", AgentSkillService.SOURCE_PROJECT,
                        ".skill/review/SKILL.md", "# Review\n\nCheck the diff.",
                        List.of(".skill/review/references/checklist.md", ".skill/review/scripts/run.sh")));

        JsonObject input = new JsonObject();
        input.addProperty("name", "review");

        ToolResult result = new SkillTool(skills).execute(context(), input);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent())
                .contains("source=\"project\"")
                .contains("Skill file: .skill/review/SKILL.md")
                .contains("<file>.skill/review/references/checklist.md</file>")
                .contains("<file>.skill/review/scripts/run.sh</file>");
    }

    @Test
    @DisplayName("找不到技能时失败并给出原因，不能静默返回空内容")
    void missingSkillFailsExplicitly() {
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.load(eq(7), any(), eq("nope"))).thenReturn(null);

        JsonObject input = new JsonObject();
        input.addProperty("name", "nope");

        ToolResult result = new SkillTool(skills).execute(context(), input);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getContent()).contains("nope");
    }

    @Test
    @DisplayName("两个来源都没有技能时，目录提示如何添加项目级技能")
    void emptyCatalogExplainsHowToAddProjectSkills() {
        AgentSkillService skills = mock(AgentSkillService.class);
        when(skills.resolveCatalog(eq(7), any())).thenReturn(List.of());

        ToolResult result = new SkillTool(skills).execute(context(), new JsonObject());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains(".skill/");
    }

    private static AgentContext context() {
        return new AgentContext("session-7", 7, null, "conversation-7", 7L,
                WORKSPACE, new ArrayList<>(), 0);
    }
}
