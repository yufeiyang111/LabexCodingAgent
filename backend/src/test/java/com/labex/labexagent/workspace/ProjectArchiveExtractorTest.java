package com.labex.labexagent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectArchiveExtractorTest {

    @TempDir
    Path tempDir;

    private ProjectArchiveExtractor extractor(long maxExtractedBytes, int maxFiles) {
        ProjectArchiveLimits limits = new ProjectArchiveLimits();
        limits.setMaxExtractedBytes(maxExtractedBytes);
        limits.setMaxExtractedFiles(maxFiles);
        return new ProjectArchiveExtractor(limits);
    }

    private Path zip(Map<String, byte[]> entries) throws IOException {
        Path archive = tempDir.resolve("project.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void skipsDependencyDirectoriesAndReportsWhatWasSkipped() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("src/main.js", bytes("console.log(1)"));
        entries.put("package.json", bytes("{}"));
        entries.put("node_modules/lodash/index.js", bytes("module.exports = 1"));
        entries.put("node_modules/lodash/package.json", bytes("{}"));
        entries.put("dist/bundle.js", bytes("bundle"));
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        ProjectArchiveExtractor.ExtractStats stats =
                extractor(10 * 1024 * 1024, 1000).extract(zip(entries), target, true);

        assertEquals(2, stats.fileCount());
        assertEquals(3, stats.skippedCount());
        assertEquals(3, stats.skippedSamples().size());
        assertTrue(Files.exists(target.resolve("src/main.js")));
        assertFalse(Files.exists(target.resolve("node_modules")));
        assertFalse(Files.exists(target.resolve("dist")));
    }

    @Test
    void keepsDependencyDirectoriesWhenTheCallerOptsOut() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.js", bytes("x"));
        entries.put("node_modules/lodash/index.js", bytes("y"));
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        ProjectArchiveExtractor.ExtractStats stats =
                extractor(10 * 1024 * 1024, 1000).extract(zip(entries), target, false);

        assertEquals(2, stats.fileCount());
        assertEquals(0, stats.skippedCount());
        assertTrue(Files.exists(target.resolve("node_modules/lodash/index.js")));
    }

    @Test
    void skippedSamplesAreBoundedSoAHugeTreeDoesNotBloatTheResult() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("index.js", bytes("entry"));
        for (int index = 0; index < 20; index++) {
            entries.put("node_modules/pkg" + index + "/index.js", bytes("x"));
        }
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        ProjectArchiveExtractor.ExtractStats stats =
                extractor(10 * 1024 * 1024, 1000).extract(zip(entries), target, true);

        assertEquals(1, stats.fileCount());
        assertEquals(20, stats.skippedCount());
        assertEquals(5, stats.skippedSamples().size());
    }

    @Test
    void rejectsArchivesThatExceedTheExtractedByteLimit() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("big.bin", new byte[3 * 1024 * 1024]);
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> extractor(2L * 1024 * 1024, 1000).extract(zip(entries), target, true));

        assertTrue(error.getMessage().contains("解压后超过上限"), error.getMessage());
    }

    @Test
    void rejectsArchivesThatExceedTheFileCountLimit() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            entries.put("file" + index + ".txt", bytes("x"));
        }
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> extractor(10 * 1024 * 1024, 3).extract(zip(entries), target, true));

        assertTrue(error.getMessage().contains("文件数超过上限"), error.getMessage());
    }

    @Test
    void rejectsEntriesThatEscapeTheTargetDirectory() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../escaped.txt", bytes("x"));
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> extractor(10 * 1024 * 1024, 1000).extract(zip(entries), target, true));

        assertTrue(error.getMessage().contains("Unsafe archive entry"), error.getMessage());
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")));
    }

    @Test
    void createsEmptyDirectoriesEvenWhenTheyCarryNoFiles() throws IOException {
        Path archive = tempDir.resolve("empty-dirs.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("src/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("src/app.js"));
            zip.write(bytes("x"));
            zip.closeEntry();
        }
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        ProjectArchiveExtractor.ExtractStats stats =
                extractor(10 * 1024 * 1024, 1000).extract(archive, target, true);

        assertEquals(1, stats.fileCount());
        assertTrue(Files.isDirectory(target.resolve("src")));
    }

    @Test
    void rejectsArchivesThatYieldNoImportableFiles() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("node_modules/lodash/index.js", bytes("x"));
        entries.put("dist/bundle.js", bytes("y"));
        Path target = Files.createDirectories(tempDir.resolve("workspace"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> extractor(10 * 1024 * 1024, 1000).extract(zip(entries), target, true));

        assertTrue(error.getMessage().contains("只有依赖或构建产物文件"), error.getMessage());
        assertFalse(Files.exists(target.resolve("node_modules")));
    }

    @Test
    void progressCallbackAdvancesForEveryEntryIncludingSkippedOnes() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", bytes("a"));
        entries.put("node_modules/pkg/index.js", bytes("b"));
        entries.put("b.txt", bytes("c"));
        Path target = Files.createDirectories(tempDir.resolve("workspace"));
        List<Integer> seen = new ArrayList<>();

        extractor(10 * 1024 * 1024, 1000).extract(zip(entries), target, true, seen::add);

        assertEquals(List.of(1, 2, 3), seen, "每个条目都应推进进度，跳过的条目也算处理过");
    }

    @Test
    void countEntriesMatchesTheCentralDirectorySoTheUiHasADenominator() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("src/main.js", bytes("x"));
        entries.put("src/style.css", bytes("y"));
        entries.put("README.md", bytes("z"));
        Path archive = zip(entries);

        assertEquals(3, extractor(10 * 1024 * 1024, 1000).countEntries(archive));
    }
}
