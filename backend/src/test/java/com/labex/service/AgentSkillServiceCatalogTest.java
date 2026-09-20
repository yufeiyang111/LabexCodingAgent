package com.labex.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.AgentSkill;
import com.labex.labexagent.skill.WorkspaceSkillScanner;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 技能目录合并规则。
 *
 * <p>"项目级覆盖用户级"是这套设计的核心承诺：用户在工作区放一份同名技能，就该生效，不必去改
 * 全局配置。这条规则错了不会有任何报错 —— 只会让项目里的技能被全局版本静默压掉。
 */
class AgentSkillServiceCatalogTest {

    private static WorkspaceSkillScanner.Discovered project(String key, String description) {
        return new WorkspaceSkillScanner.Discovered(key, key, description,
                ".skill/" + key + "/SKILL.md");
    }

    private static AgentSkill user(String key, String title, String description) {
        AgentSkill skill = new AgentSkill();
        skill.setSkillKey(key);
        skill.setTitle(title);
        skill.setDescription(description);
        return skill;
    }

    @Test
    @DisplayName("项目级与用户级同时存在时，项目级排在前面且标注来源")
    void projectSkillsComeFirstAndAreLabelled() {
        List<AgentSkillService.CatalogEntry> merged = AgentSkillService.mergeCatalog(
                List.of(project("review", "项目级审查")), List.of(user("deploy", "deploy", "发布流程")));

        assertThat(merged).extracting(AgentSkillService.CatalogEntry::skillKey)
                .containsExactly("review", "deploy");
        assertThat(merged.get(0).source()).isEqualTo(AgentSkillService.SOURCE_PROJECT);
        assertThat(merged.get(0).relativePath()).isEqualTo(".skill/review/SKILL.md");
        assertThat(merged.get(1).source()).isEqualTo(AgentSkillService.SOURCE_USER);
        assertThat(merged.get(1).relativePath()).isNull();
    }

    @Test
    @DisplayName("同名时项目级覆盖用户级 —— 项目里那份的描述生效，且不出现重复条目")
    void projectOverridesUserForSameKey() {
        List<AgentSkillService.CatalogEntry> merged = AgentSkillService.mergeCatalog(
                List.of(project("review", "项目里的审查规则")),
                List.of(user("review", "全局 review", "全局的审查规则")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).description()).isEqualTo("项目里的审查规则");
        assertThat(merged.get(0).source()).isEqualTo(AgentSkillService.SOURCE_PROJECT);
    }

    @Test
    @DisplayName("用户级键做归一化后再判重，大小写与空格不产生重复")
    void userKeysAreNormalizedBeforeDeduplication() {
        List<AgentSkillService.CatalogEntry> merged = AgentSkillService.mergeCatalog(
                List.of(project("code-review", "project")),
                List.of(user("  Code Review  ", "全局", "global")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).source()).isEqualTo(AgentSkillService.SOURCE_PROJECT);
    }

    @Test
    @DisplayName("只有用户级技能时照常返回，不影响既有行为")
    void userOnlyCatalogStillWorks() {
        List<AgentSkillService.CatalogEntry> merged = AgentSkillService.mergeCatalog(
                List.of(), List.of(user("deploy", "deploy", "发布流程")));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).source()).isEqualTo(AgentSkillService.SOURCE_USER);
    }

    @Test
    @DisplayName("两层都空时返回空列表，渲染为空串而不是半个标签")
    void emptyCatalogRendersNothing() {
        assertThat(AgentSkillService.mergeCatalog(List.of(), List.of())).isEmpty();
        assertThat(AgentSkillService.renderCatalogBlock(List.of())).isEmpty();
        assertThat(AgentSkillService.renderCatalogBlock(null)).isEmpty();
    }

    @Test
    @DisplayName("技能总数超限时截断，且项目级优先保留")
    void catalogIsCapped() {
        List<WorkspaceSkillScanner.Discovered> many = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> project("p" + i, "d")).toList();

        List<AgentSkillService.CatalogEntry> merged = AgentSkillService.mergeCatalog(many, List.of());

        assertThat(merged).hasSize(12);
        assertThat(merged.get(0).skillKey()).isEqualTo("p0");
    }

    @Test
    @DisplayName("目录块只含名字/描述/来源，绝不含技能正文")
    void catalogBlockCarriesNoBody() {
        String block = AgentSkillService.renderCatalogBlock(List.of(
                new AgentSkillService.CatalogEntry("review", "review", "检查 diff",
                        AgentSkillService.SOURCE_PROJECT, ".skill/review/SKILL.md")));

        assertThat(block).contains("<available_skills>", "<name>review</name>", "检查 diff");
        assertThat(block).contains("<location>.skill/review/SKILL.md</location>");
        assertThat(block).doesNotContain("# review");
    }

    @Test
    @DisplayName("描述里的尖括号与引号被转义，不能破坏外层 XML 结构")
    void catalogBlockEscapesMarkup() {
        String block = AgentSkillService.renderCatalogBlock(List.of(
                new AgentSkillService.CatalogEntry("x", "x", "use <script> \"quoted\"",
                        AgentSkillService.SOURCE_USER, null)));

        assertThat(block).doesNotContain("<script>");
        assertThat(block).contains("&lt;script&gt;").contains("&quot;quoted&quot;");
        assertThat(block).doesNotContain("<location>");
    }
}
