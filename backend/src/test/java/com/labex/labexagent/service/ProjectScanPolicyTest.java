package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.labexagent.workspace.SecureWorkspacePath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * .labex-agentignore 的两种加载语义：扫描用 loadIgnoreRules（有默认回落与内置目录硬底），
 * 导出/下载用 loadDeclaredIgnoreRules（只看用户声明，缺失或异常即退化为空规则）。
 */
class ProjectScanPolicyTest {

    @TempDir
    Path workspace;

    private SecureWorkspacePath paths() {
        return new SecureWorkspacePath(workspace);
    }

    @Test
    void scanModeFallsBackToBuiltInDefaultsWhenFileIsMissing() {
        var rules = ProjectScanPolicy.loadIgnoreRules(paths());

        assertThat(rules.shouldSkipEntry(workspace.resolve("node_modules"), true)).isTrue();
        assertThat(rules.shouldSkipEntry(workspace.resolve(".idea"), true)).isTrue();
    }

    @Test
    void declaredModeHasNoPatternsWhenFileIsMissing() {
        var rules = ProjectScanPolicy.loadDeclaredIgnoreRules(paths());

        assertThat(rules.hasPatterns()).isFalse();
        assertThat(rules.shouldSkipEntry(workspace.resolve("node_modules"), true)).isFalse();
    }

    @Test
    void declaredModeReadsPatternsAndSkipsUnsupportedNegation() throws Exception {
        Files.writeString(workspace.resolve(".labex-agentignore"),
                "# comment\n\n!keep.log\n/logs/\n*.secret\n", StandardCharsets.UTF_8);

        var rules = ProjectScanPolicy.loadDeclaredIgnoreRules(paths());

        assertThat(rules.hasPatterns()).isTrue();
        assertThat(rules.shouldSkipEntry(workspace.resolve("logs"), true)).isTrue();
        assertThat(rules.shouldSkipEntry(workspace.resolve("config").resolve("app.secret"), false)).isTrue();
        assertThat(rules.shouldSkipEntry(workspace.resolve("src").resolve("Main.java"), false)).isFalse();
    }

    @Test
    void declaredModeTreatsNonRegularFileAsMissing() throws Exception {
        Files.createDirectories(workspace.resolve(".labex-agentignore"));

        assertThat(ProjectScanPolicy.loadDeclaredIgnoreRules(paths()).hasPatterns()).isFalse();
    }

    @Test
    void declaredModeTreatsUndecodableFileAsMissing() throws Exception {
        Files.write(workspace.resolve(".labex-agentignore"), new byte[] {'l', 'o', 'g', 's', '/', (byte) 0xC3});

        assertThat(ProjectScanPolicy.loadDeclaredIgnoreRules(paths()).hasPatterns()).isFalse();
    }
}
