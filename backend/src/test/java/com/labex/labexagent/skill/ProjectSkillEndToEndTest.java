package com.labex.labexagent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.labex.entity.AgentSkill;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.impl.SkillTool;
import com.labex.service.AgentSkillService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 端到端：在真实文件系统上把「工作区放技能 → 扫到 → 注入提示词 → 工具加载」整条链路跑通。
 *
 * <p>这是唯一能证明"用户往 .skill/ 放文件就能被读到"的测试 —— 其余单测都只覆盖单个环节。
 * 尤其要锁住"项目级覆盖用户级"在真实扫描路径上也成立，而不只在纯函数里成立。
 */
class ProjectSkillEndToEndTest {

    /** 只实现扫描所需的行为，避免为了跑扫描去起整个 MyBatis。 */
    private static class ScannerBackedSkillService extends AgentSkillService {
        private final List<AgentSkill> userSkills;

        ScannerBackedSkillService(List<AgentSkill> userSkills) {
            this.userSkills = userSkills;
        }

        @Override
        public List<CatalogEntry> resolveCatalog(Integer studentId, Path workspaceRoot) {
            return mergeCatalog(WorkspaceSkillScanner.scan(workspaceRoot), userSkills);
        }

        @Override
        public LoadedSkill load(Integer studentId, Path workspaceRoot, String name) {
            String wanted = name == null ? "" : name.trim();
            for (WorkspaceSkillScanner.Discovered skill : WorkspaceSkillScanner.scan(workspaceRoot)) {
                if (!skill.skillKey().equalsIgnoreCase(wanted)) {
                    continue;
                }
                String content = WorkspaceSkillScanner.readBody(workspaceRoot, skill.relativePath());
                if (content == null) {
                    return null;
                }
                return new LoadedSkill(skill.skillKey(), skill.title(), SOURCE_PROJECT, skill.relativePath(),
                        content, WorkspaceSkillScanner.listSiblingFiles(workspaceRoot, skill.relativePath(), 20));
            }
            for (AgentSkill skill : userSkills) {
                if (skill.getSkillKey().equalsIgnoreCase(wanted)) {
                    return new LoadedSkill(skill.getSkillKey(), skill.getTitle(), SOURCE_USER, null,
                            skill.getContent(), List.of());
                }
            }
            return null;
        }

        @Override
        public boolean hasAnySkill(Integer studentId, Path workspaceRoot) {
            return !WorkspaceSkillScanner.scan(workspaceRoot).isEmpty() || !userSkills.isEmpty();
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static AgentContext context(Path workspace) {
        return new AgentContext("s", 7, null, "c", 1L, workspace, new ArrayList<>(), 0);
    }

    private static AgentSkill userSkill(String key, String content) {
        AgentSkill skill = new AgentSkill();
        skill.setSkillKey(key);
        skill.setTitle(key);
        skill.setDescription("user level " + key);
        skill.setContent(content);
        return skill;
    }

    @Test
    @DisplayName("往 .skill/ 放一个技能，从提问到加载正文全链路可用，含 references 与 scripts")
    void projectSkillIsDiscoverableAndLoadableWithItsFiles(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/sql-review/SKILL.md"), """
                ---
                name: sql-review
                description: Use when reviewing SQL migrations for safety
                ---

                # SQL Review

                Read references/checklist.md then run scripts/lint.sh.
                """);
        write(workspace.resolve(".skill/sql-review/references/checklist.md"), "# Checklist\n\n- no full scans");
        write(workspace.resolve(".skill/sql-review/scripts/lint.sh"), "#!/bin/sh\necho lint");

        AgentSkillService service = new ScannerBackedSkillService(List.of());
        SkillTool tool = new SkillTool(service);

        // ① 能力判定：只有项目级技能时，skill 工具也必须可用
        assertThat(service.hasAnySkill(7, workspace)).isTrue();

        // ② 不传 name：只列目录
        ToolResult catalog = tool.execute(context(workspace), new JsonObject());
        assertThat(catalog.isSuccess()).isTrue();
        assertThat(catalog.getContent()).contains("sql-review").contains("[project]");

        // ③ 传 name：拿到正文 + 基准文件 + 兄弟文件
        JsonObject input = new JsonObject();
        input.addProperty("name", "sql-review");
        ToolResult loaded = tool.execute(context(workspace), input);

        assertThat(loaded.isSuccess()).isTrue();
        assertThat(loaded.getContent())
                .contains("# SQL Review")
                .contains("source=\"project\"")
                .contains("Skill file: .skill/sql-review/SKILL.md")
                .contains("<file>.skill/sql-review/references/checklist.md</file>")
                .contains("<file>.skill/sql-review/scripts/lint.sh</file>");
        // frontmatter 不该泄漏到正文里
        assertThat(loaded.getContent()).doesNotContain("description: Use when reviewing SQL");
    }

    @Test
    @DisplayName("项目里的同名技能覆盖用户级版本 —— 真实扫描路径上也要成立")
    void projectSkillOverridesUserSkillOnTheRealScanPath(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/deploy/SKILL.md"), """
                ---
                name: deploy
                description: project specific deploy rules
                ---
                # Project Deploy
                """);

        AgentSkillService service = new ScannerBackedSkillService(List.of(userSkill("deploy", "# Global Deploy")));
        SkillTool tool = new SkillTool(service);

        ToolResult catalog = tool.execute(context(workspace), new JsonObject());
        assertThat(catalog.getContent()).contains("[project]").doesNotContain("[user]");

        JsonObject input = new JsonObject();
        input.addProperty("name", "deploy");
        ToolResult loaded = tool.execute(context(workspace), input);

        assertThat(loaded.getContent()).contains("# Project Deploy").doesNotContain("# Global Deploy");
    }

    @Test
    @DisplayName("没有 .skill/ 时用户级技能照常工作，不受影响")
    void userSkillStillWorksWithoutProjectDirectory(@TempDir Path workspace) {
        AgentSkillService service = new ScannerBackedSkillService(List.of(userSkill("deploy", "# Global Deploy")));
        SkillTool tool = new SkillTool(service);

        JsonObject input = new JsonObject();
        input.addProperty("name", "deploy");
        ToolResult loaded = tool.execute(context(workspace), input);

        assertThat(loaded.isSuccess()).isTrue();
        assertThat(loaded.getContent()).contains("# Global Deploy").contains("source=\"user\"");
    }

    @Test
    @DisplayName("空工作区且无用户技能时，工具仍可用但明确说明如何添加技能")
    void emptyStateIsExplicit(@TempDir Path workspace) {
        AgentSkillService service = new ScannerBackedSkillService(List.of());
        SkillTool tool = new SkillTool(service);

        assertThat(service.hasAnySkill(7, workspace)).isFalse();
        ToolResult catalog = tool.execute(context(workspace), new JsonObject());
        assertThat(catalog.isSuccess()).isTrue();
        assertThat(catalog.getContent()).contains(".skill/");
    }
}
