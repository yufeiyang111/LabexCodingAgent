package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.entity.StudentProject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalContextServiceTest {

    @TempDir
    Path workspace;

    @Test
    void reusesUnchangedDocumentsAndReindexesOnlyChangedFiles() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/InvoiceService.java"), """
                package demo;
                public class InvoiceService {
                    public int calculateInvoice(int units) { return units * 9; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src/OrderController.java"), """
                @GetMapping("/orders")
                public class OrderController { }
                """, StandardCharsets.UTF_8);
        StudentProject project = project();
        IncrementalContextService service = new IncrementalContextService();

        IncrementalContextService.IndexSnapshot initial = service.index(project);
        IncrementalContextService.IndexSnapshot unchanged = service.index(project);
        Files.writeString(workspace.resolve("src/InvoiceService.java"), "\n// changed", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        IncrementalContextService.IndexSnapshot changed = service.index(project);

        assertEquals(2, initial.stats().indexedFiles());
        assertEquals(0, initial.stats().reusedFiles());
        assertEquals(2, unchanged.stats().reusedFiles());
        assertEquals(0, unchanged.stats().changedFiles());
        assertEquals(1, changed.stats().changedFiles());
        assertEquals(1, changed.stats().reusedFiles());
    }

    @Test
    void skipsAgentRuntimeAndPackageCacheDirectoriesDuringIndexing() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve(".labex/agent-logs"));
        Files.createDirectories(workspace.resolve("npm-cache/content"));
        Files.createDirectories(workspace.resolve("AppData/runtime"));
        Files.writeString(workspace.resolve("src/App.java"), "public class App { }", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".labex/agent-logs/run.md"), "agent runtime log", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("npm-cache/content/cache.json"), "{\"cached\":true}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("AppData/runtime/settings.json"), "{\"runtime\":true}", StandardCharsets.UTF_8);

        IncrementalContextService.IndexSnapshot snapshot = new IncrementalContextService().index(project());

        assertEquals(1, snapshot.stats().indexedFiles());
        assertEquals("src/App.java", snapshot.documents().get(0).path());
    }

    @Test
    void reportsBoundedScanStatusWhenIndexingLargeWorkspaces() throws Exception {
        Path source = Files.createDirectories(workspace.resolve("src"));
        for (int index = 0; index < 1_000; index++) {
            Files.writeString(source.resolve("Source" + index + ".java"), "class Source" + index + " { }",
                    StandardCharsets.UTF_8);
        }

        IncrementalContextService.IndexSnapshot snapshot = new IncrementalContextService().index(project());

        assertTrue(snapshot.scanStatus().truncated());
        assertTrue(snapshot.stats().indexedFiles() <= 900);
    }

    @Test
    void boundsRetainedIndexesAcrossWorkspaces() throws Exception {
        IncrementalContextCache cache = new IncrementalContextCache();
        IncrementalContextService service = new IncrementalContextService(null, new WorkspaceScanner(), cache);
        for (int index = 0; index < 9; index++) {
            Path root = Files.createDirectories(workspace.resolve("workspace-" + index).resolve("src"));
            Files.writeString(root.resolve("App.java"), "class App" + index + " { }", StandardCharsets.UTF_8);
            StudentProject project = new StudentProject();
            project.setWorkspacePath(root.getParent().toString());
            service.index(project);
        }

        assertEquals(8, cache.stats().workspaces());
    }

    @Test
    void retrievesIdentifierNaturalLanguageRouteTestAndPriorityQueriesWithReasons() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("test"));
        Files.writeString(workspace.resolve("src/InvoiceService.java"), """
                public class InvoiceService {
                    public int calculateInvoice(int units) { return units * 9; }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src/OrderController.java"), """
                @GetMapping("/orders")
                public class OrderController { }
                """, StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("test/InvoiceServiceTest.java"), """
                class InvoiceServiceTest { void calculatesInvoice() { } }
                """, StandardCharsets.UTF_8);
        IncrementalContextService service = new IncrementalContextService();
        StudentProject project = project();

        IncrementalContextService.RetrievalResult identifier = service.retrieve(project, "calculateInvoice", List.of(), 5);
        IncrementalContextService.RetrievalResult naturalLanguage = service.retrieve(project, "invoice calculation", List.of(), 5);
        IncrementalContextService.RetrievalResult route = service.retrieve(project, "/orders", List.of(), 5);
        IncrementalContextService.RetrievalResult test = service.retrieve(project, "InvoiceServiceTest", List.of(), 5);
        IncrementalContextService.RetrievalResult priority = service.retrieve(project, "unrelated", List.of("src/OrderController.java"), 5);

        assertTrue(identifier.hits().stream().anyMatch(hit -> hit.path().equals("src/InvoiceService.java")));
        assertTrue(naturalLanguage.hits().stream().anyMatch(hit -> hit.path().equals("src/InvoiceService.java")));
        assertTrue(route.hits().stream().anyMatch(hit -> hit.path().equals("src/OrderController.java")));
        assertTrue(test.hits().stream().anyMatch(hit -> hit.path().equals("test/InvoiceServiceTest.java")));
        assertEquals("src/OrderController.java", priority.hits().get(0).path());
        assertFalse(identifier.hits().get(0).reasons().isEmpty());
        assertTrue(identifier.elapsedMillis() >= 0);
    }

    @Test
    void optionalEmbeddingRankingIsDisabledByDefaultAndCanBeEnabledExplicitly() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/InvoiceService.java"),
                "public class InvoiceService { int calculateInvoice() { return 1; } }", StandardCharsets.UTF_8);
        IncrementalContextService service = new IncrementalContextService();
        assertFalse(service.embeddingsEnabled());

        String previous = System.getProperty("labex.agent.context.embeddings.enabled");
        System.setProperty("labex.agent.context.embeddings.enabled", "true");
        try {
            assertTrue(service.embeddingsEnabled());
            assertTrue(service.retrieve(project(), "invoice calculation", List.of(), 5).hits().get(0).reasons()
                    .stream().anyMatch(reason -> reason.startsWith("embedding:")));
        } finally {
            if (previous == null) System.clearProperty("labex.agent.context.embeddings.enabled");
            else System.setProperty("labex.agent.context.embeddings.enabled", previous);
        }
    }

    @Test
    void benchmarksColdAndIncrementalRetrievalWithComparableTopHits() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/InvoiceService.java"),
                "public class InvoiceService { int calculateInvoice() { return 1; } }", StandardCharsets.UTF_8);
        IncrementalContextService service = new IncrementalContextService();
        service.index(project());

        IncrementalContextService.BenchmarkReport report = service.benchmark(project(), List.of(
                new IncrementalContextService.BenchmarkCase("identifier", "calculateInvoice", List.of()),
                new IncrementalContextService.BenchmarkCase("priority", "other", List.of("src/InvoiceService.java"))), 5);

        assertEquals(2, report.samples().size());
        assertTrue(report.samples().stream().allMatch(sample -> sample.coldElapsedMillis() >= 0));
        assertTrue(report.samples().stream().allMatch(sample -> sample.incrementalElapsedMillis() >= 0));
        assertTrue(report.samples().stream().allMatch(sample -> sample.topHitOverlap() > 0.0));
    }

    private StudentProject project() {
        StudentProject project = new StudentProject();
        project.setWorkspacePath(workspace.toString());
        return project;
    }
}
