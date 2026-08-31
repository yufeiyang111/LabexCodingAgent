package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTextSearchServiceTest {

    @TempDir
    Path workspace;

    private SecureWorkspacePath paths;
    private WorkspaceFileOperationProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(workspace.resolve("App.java"), "class App {\n    void helloWorld() {}\n}\n");
        Files.writeString(workspace.resolve("notes.md"), "# Notes\nHELLO WORLD in markdown\n");
        Path dependency = Files.createDirectories(workspace.resolve("node_modules").resolve("pkg"));
        Files.writeString(dependency.resolve("index.js"), "hello world inside dependency");
        byte[] binary = {0x00, 0x01, 0x02, 'h', 'i'};
        Files.write(workspace.resolve("blob.bin"), binary);
        properties = new WorkspaceFileOperationProperties();
        paths = new SecureWorkspacePath(workspace);
    }

    private WorkspaceTextSearchService.SearchQuery query(String keyword, boolean regex,
                                                         boolean caseSensitive, String include) {
        return new WorkspaceTextSearchService.SearchQuery(keyword, regex, caseSensitive, include, 200);
    }

    @Test
    void findsMatchesWithRelativePathAndLineNumber() {
        WorkspaceTextSearchService service = new WorkspaceTextSearchService(new WorkspaceScanner(), properties);

        var result = service.search(paths, workspace, query("hello", false, false, null));

        assertTrue(result.complete());
        assertEquals(2, result.hits().size());
        var first = result.hits().get(0);
        assertEquals("App.java", first.path());
        assertEquals(2, first.lineNumber());
        assertTrue(first.lineText().contains("helloWorld"));
        var second = result.hits().get(1);
        assertEquals("notes.md", second.path());
        assertTrue(second.lineText().contains("HELLO WORLD"));
    }

    @Test
    void respectsCaseSensitivityFlag() {
        WorkspaceTextSearchService service = new WorkspaceTextSearchService(new WorkspaceScanner(), properties);

        // "hello" 小写敏感模式只命中 App.java（helloWorld），不敏感模式连 notes.md 的 HELLO 一起命中。
        var sensitive = service.search(paths, workspace, query("hello", false, true, null));
        var insensitive = service.search(paths, workspace, query("hello", false, false, null));

        assertTrue(sensitive.hits().stream().allMatch(hit -> hit.path().equals("App.java")));
        assertEquals(1, sensitive.hits().size());
        assertEquals(2, insensitive.hits().size());
    }

    @Test
    void supportsRegexModeAndIncludeFilter() {
        WorkspaceTextSearchService service = new WorkspaceTextSearchService(new WorkspaceScanner(), properties);

        var regexResult = service.search(paths, workspace, query("void\\s+\\w+\\(", true, false, null));
        assertEquals(1, regexResult.hits().size());

        var includeResult = service.search(paths, workspace, query("hello", false, false, ".md"));
        assertEquals(1, includeResult.hits().size());
        assertEquals("notes.md", includeResult.hits().get(0).path());
    }

    @Test
    void skipsBinaryFilesAndIgnoredDirectories() {
        WorkspaceTextSearchService service = new WorkspaceTextSearchService(new WorkspaceScanner(), properties);

        var result = service.search(paths, workspace, query("hello", false, false, null));

        List<String> hitPaths = result.hits().stream().map(hit -> hit.path()).toList();
        assertFalse(hitPaths.contains("blob.bin"));
        assertFalse(hitPaths.stream().anyMatch(path -> path.startsWith("node_modules/")));
    }

    @Test
    void truncatesResultsAtConfiguredLimit() throws Exception {
        properties.setSearchMaxResults(1);
        WorkspaceTextSearchService service = new WorkspaceTextSearchService(new WorkspaceScanner(), properties);

        var result = service.search(paths, workspace, query("hello", false, false, null));

        assertEquals(1, result.hits().size());
        assertFalse(result.complete());
    }

    @Test
    void rejectsBlankKeywordAndInvalidRegex() {
        WorkspaceTextSearchService service = new WorkspaceTextSearchService(new WorkspaceScanner(), properties);

        assertThrows(IllegalArgumentException.class,
                () -> service.search(paths, workspace, query("   ", false, false, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.search(paths, workspace, query("(unclosed[", true, false, null)));
    }
}
