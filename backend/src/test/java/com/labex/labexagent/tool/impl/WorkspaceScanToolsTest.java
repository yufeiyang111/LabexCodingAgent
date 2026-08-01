package com.labex.labexagent.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.service.WorkspaceScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceScanToolsTest {
    @TempDir
    Path workspace;

    @Test
    void globPrunesCacheAndProfileDirectoriesBeforeMatching() throws Exception {
        write("src/App.java", "public class App { }");
        write("src/component.jsx", "export default {};");
        write("README.md", "project documentation");
        write("npm-cache/content-v2/sha512/00/dependency.java", "public class Dependency { }");
        write("AppData/Microsoft/cache/ignored.java", "public class Ignored { }");

        ToolResultView javaResult = executeGlob("**/*.java", 20);
        ToolResultView braceResult = executeGlob("**/*.{java,jsx,js,yml,yaml,xml,json,md}", 20);

        assertThat(javaResult.content()).contains("src/App.java");
        assertThat(javaResult.content()).doesNotContain("npm-cache").doesNotContain("AppData");
        assertThat(braceResult.content()).contains("src/App.java", "src/component.jsx", "README.md");
        assertThat(braceResult.content()).doesNotContain("npm-cache").doesNotContain("AppData");
    }

    @Test
    void globStopsAfterTheRequestedResultLimit() throws Exception {
        write("src/First.java", "class First { }");
        write("src/Second.java", "class Second { }");

        ToolResultView result = executeGlob("**/*.java", 1);

        assertThat(result.content()).contains("Scan truncated: result_limit");
        assertThat(result.content().lines().filter(line -> line.endsWith(".java")).count()).isEqualTo(1);
    }


    @Test
    void grepSkipsCacheAndProfileDirectories() throws Exception {
        write("src/App.java", "class App { String marker = \"needle\"; }");
        write("npm-cache/content-v2/sha512/00/cache.txt", "needle");
        write("AppData/Microsoft/cache/settings.txt", "needle");

        ToolResultView result = executeGrep("needle", 20);

        assertThat(result.content()).contains("src/App.java:1:");
        assertThat(result.content()).doesNotContain("npm-cache").doesNotContain("AppData");
    }

    @Test
    void listFilesHidesCacheAndProfileDirectories() throws Exception {
        write("src/App.java", "public class App { }");
        write("npm-cache/content/cache.json", "{}");
        write("AppData/Microsoft/settings.json", "{}");

        JsonObject args = new JsonObject();
        args.addProperty("max_depth", 3);
        var result = new ListFilesTool(new WorkspaceScanner()).execute(context(), args);

        assertThat(result.getContent()).contains("[D] src/").contains("[F] App.java");
        assertThat(result.getContent()).doesNotContain("npm-cache").doesNotContain("AppData");
    }

    @Test
    void listFilesStopsAtTheEntryLimit() throws Exception {
        for (int index = 0; index < 501; index++) {
            write("src/File" + index + ".java", "class File" + index + " { }");
        }

        JsonObject args = new JsonObject();
        args.addProperty("max_depth", 2);
        var result = new ListFilesTool(new WorkspaceScanner()).execute(context(), args);

        assertThat(result.getContent()).contains("Listing truncated: entry limit reached (max_entries=500).");
        assertThat(result.getContent().lines().filter(line -> line.startsWith("  [F]")).count()).isEqualTo(499);
    }

    private ToolResultView executeGlob(String pattern, int maxResults) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("pattern", pattern);
        args.addProperty("max_results", maxResults);
        var result = new GlobTool(new WorkspaceScanner()).execute(context(), args);
        return new ToolResultView(result.getContent());
    }

    private ToolResultView executeGrep(String pattern, int maxResults) throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("pattern", pattern);
        args.addProperty("max_results", maxResults);
        var result = new GrepTool(new WorkspaceScanner()).execute(context(), args);
        return new ToolResultView(result.getContent());
    }

    private AgentContext context() {
        return new AgentContext("session", 1, null, "conversation", 1L, workspace,
                new ArrayList<>(), 0);
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private record ToolResultView(String content) {
    }


    @Test
    void agentToolsApplyWorkspaceIgnoreFile() throws Exception {
        write("src/App.java", "class App { String marker = \"needle\"; }");
        write("logs/application.log", "needle");
        Files.writeString(workspace.resolve(".labex-agentignore"), "logs/\n", StandardCharsets.UTF_8);

        ToolResultView grep = executeGrep("needle", 20);
        ToolResultView glob = executeGlob("**/*", 20);
        JsonObject args = new JsonObject();
        args.addProperty("max_depth", 3);
        var list = new ListFilesTool(new WorkspaceScanner()).execute(context(), args);

        assertThat(grep.content()).contains("src/App.java:1:").doesNotContain("logs/application.log");
        assertThat(glob.content()).contains("src/App.java").doesNotContain("logs/application.log");
        assertThat(list.getContent()).contains("[D] src/").doesNotContain("logs");
    }

}
