package com.labex.labexagent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 项目级 Skill 扫描的行为测试。
 *
 * <p>这些用例锁定的都是"静默失效"类的风险：目录没扫到、frontmatter 没解析、噪声目录混入、
 * 越界读取 —— 任何一条坏了都不会报错，只会让模型看不到技能。
 */
class WorkspaceSkillScannerTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String skill(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n";
    }

    @Test
    @DisplayName("没有 .skill 目录时返回空列表，不抛异常")
    void absentSkillDirectoryYieldsEmptyList(@TempDir Path workspace) {
        assertThat(WorkspaceSkillScanner.scan(workspace)).isEmpty();
        assertThat(WorkspaceSkillScanner.scan(null)).isEmpty();
    }

    @Test
    @DisplayName("扫描 .skill/<name>/SKILL.md 并解析 frontmatter")
    void discoversSkillWithFrontmatter(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/review/SKILL.md"),
                skill("code-review", "Use when reviewing a diff for correctness", "# Code Review\n\nSteps..."));

        List<WorkspaceSkillScanner.Discovered> found = WorkspaceSkillScanner.scan(workspace);

        assertThat(found).hasSize(1);
        WorkspaceSkillScanner.Discovered skill = found.get(0);
        assertThat(skill.skillKey()).isEqualTo("code-review");
        assertThat(skill.title()).isEqualTo("code-review");
        assertThat(skill.description()).isEqualTo("Use when reviewing a diff for correctness");
        assertThat(skill.relativePath()).isEqualTo(".skill/review/SKILL.md");
    }

    @Test
    @DisplayName("缺少 frontmatter 时用目录名与正文首行兜底，而不是静默丢弃")
    void fallsBackWhenFrontmatterMissing(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/drawio/SKILL.md"), "# DrawIO diagrams\n\nUse for architecture charts.\n");

        List<WorkspaceSkillScanner.Discovered> found = WorkspaceSkillScanner.scan(workspace);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).skillKey()).isEqualTo("drawio");
        assertThat(found.get(0).description()).contains("DrawIO diagrams");
    }

    @Test
    @DisplayName("同级多个技能都被发现，且按路径稳定排序")
    void discoversMultipleSkillsDeterministically(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/beta/SKILL.md"), skill("beta", "second", "b"));
        write(workspace.resolve(".skill/alpha/SKILL.md"), skill("alpha", "first", "a"));

        List<WorkspaceSkillScanner.Discovered> found = WorkspaceSkillScanner.scan(workspace);

        assertThat(found).extracting(WorkspaceSkillScanner.Discovered::skillKey)
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("node_modules 与 __pycache__ 里的 SKILL.md 不得混入（真实故障：噪声挤爆正文）")
    void ignoresNoiseDirectories(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/pptx/SKILL.md"), skill("pptx", "make slides", "body"));
        write(workspace.resolve(".skill/pptx/node_modules/pkg/SKILL.md"), skill("noise-nm", "deps", "x"));
        write(workspace.resolve(".skill/pptx/__pycache__/x/SKILL.md"), skill("noise-pyc", "cache", "x"));

        List<WorkspaceSkillScanner.Discovered> found = WorkspaceSkillScanner.scan(workspace);

        assertThat(found).extracting(WorkspaceSkillScanner.Discovered::skillKey).containsExactly("pptx");
    }

    @Test
    @DisplayName("任意深度的嵌套技能都能扫到，但超过深度上限即停止")
    void respectsDepthLimit(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/a/b/c/SKILL.md"), skill("deep-ok", "shallow enough", "x"));
        write(workspace.resolve(".skill/a/b/c/d/e/f/SKILL.md"), skill("too-deep", "beyond limit", "x"));

        List<WorkspaceSkillScanner.Discovered> found = WorkspaceSkillScanner.scan(workspace);

        assertThat(found).extracting(WorkspaceSkillScanner.Discovered::skillKey).contains("deep-ok");
        assertThat(found).extracting(WorkspaceSkillScanner.Discovered::skillKey).doesNotContain("too-deep");
    }

    @Test
    @DisplayName("读取正文会剥掉 frontmatter，只把技能正文交给模型")
    void readBodyStripsFrontmatter(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/review/SKILL.md"),
                skill("review", "desc", "# Review\n\nCheck the diff."));

        String body = WorkspaceSkillScanner.readBody(workspace, ".skill/review/SKILL.md");

        assertThat(body).isEqualTo("# Review\n\nCheck the diff.");
        assertThat(body).doesNotContain("---");
        assertThat(body).doesNotContain("description:");
    }

    @Test
    @DisplayName("正文读取拒绝越出工作区的路径（符号链接与 .. 都不能逃逸）")
    void readBodyRejectsEscape(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/review/SKILL.md"), skill("review", "d", "x"));

        assertThat(WorkspaceSkillScanner.readBody(workspace, "../../etc/passwd")).isNull();
        assertThat(WorkspaceSkillScanner.readBody(workspace, ".skill/review/missing.md")).isNull();
        assertThat(WorkspaceSkillScanner.readBody(workspace, null)).isNull();
    }

    @Test
    @DisplayName("列出技能目录下的兄弟文件，供模型按需读取（references/scripts/data）")
    void listsSiblingFilesForOnDemandReading(@TempDir Path workspace) throws IOException {
        write(workspace.resolve(".skill/review/SKILL.md"), skill("review", "d", "x"));
        write(workspace.resolve(".skill/review/references/checklist.md"), "# checklist");
        write(workspace.resolve(".skill/review/scripts/run.sh"), "echo hi");
        write(workspace.resolve(".skill/review/node_modules/dep/SKILL.md"), "noise");

        List<String> files = WorkspaceSkillScanner.listSiblingFiles(workspace, ".skill/review/SKILL.md", 10);

        assertThat(files).containsExactly(".skill/review/references/checklist.md", ".skill/review/scripts/run.sh");
        assertThat(files).noneMatch(path -> path.contains("node_modules"));
        assertThat(files).noneMatch(path -> path.endsWith("SKILL.md"));
    }

    @Test
    @DisplayName("技能数量超过上限时截断，避免大型工作区拖垮请求")
    void capsDiscoveredSkillCount(@TempDir Path workspace) throws IOException {
        for (int i = 0; i < 60; i++) {
            write(workspace.resolve(".skill/skill-" + String.format("%02d", i) + "/SKILL.md"),
                    skill("skill-" + i, "d", "x"));
        }

        assertThat(WorkspaceSkillScanner.scan(workspace)).hasSize(50);
    }

    @Test
    @DisplayName("技能目录本身为空或只有一个目录时不报错")
    void toleratesEmptyLayout(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve(".skill"));

        assertThat(WorkspaceSkillScanner.scan(workspace)).isEmpty();
        assertThat(WorkspaceSkillScanner.listSiblingFiles(workspace, ".skill/none/SKILL.md", 5)).isEmpty();
    }
}
