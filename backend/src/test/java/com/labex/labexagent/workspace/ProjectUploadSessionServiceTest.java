package com.labex.labexagent.workspace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectUploadSessionServiceTest {

    @TempDir
    Path tempDir;

    /** 1 KiB 分片便于在一个测试里凑出多片，而不必构造 MB 级数据。 */
    private ProjectArchiveLimits limits(int chunkSize, long maxArchiveBytes) {
        ProjectArchiveLimits limits = new ProjectArchiveLimits();
        limits.setStagingDirectory(tempDir.resolve("staging").toString());
        limits.setChunkSizeBytes(chunkSize);
        limits.setMaxArchiveBytes(maxArchiveBytes);
        limits.setMinFreeDiskBytes(0);
        return limits;
    }

    private ProjectUploadSessionService service(ProjectArchiveLimits limits, ProjectUploadSessionService.Importer importer) {
        ProjectUploadSessionService service = new ProjectUploadSessionService(
                limits, new ProjectUploadStagingDirectory(limits, tempDir.toString()),
                new ProjectArchiveExtractor(limits));
        service.registerImporter(importer);
        return service;
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) ('a' + index % 26);
        }
        return data;
    }

    private static byte[] slice(byte[] source, int from, int length) {
        return Arrays.copyOfRange(source, from, Math.min(source.length, from + length));
    }

    @Test
    void assemblesChunksInOrderAndHandsTheExactBytesToTheImporter() throws Exception {
        byte[] archive = payload(2500);
        AtomicReference<byte[]> received = new AtomicReference<>();
        CountDownLatch imported = new CountDownLatch(1);
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024), (studentId, path, name, project, skip, listener) -> {
            received.set(Files.readAllBytes(path));
            imported.countDown();
            return new ProjectUploadSessionService.ImportOutcome(11, project, 3, 42L, 0, List.of());
        });

        ProjectUploadSessionService.SessionView created =
                service.create(7, "project.zip", archive.length, "demo", true);
        assertEquals(3, created.totalChunks());
        assertEquals(0, created.receivedChunks());

        service.writeChunk(7, created.sessionId(), 2, slice(archive, 2048, 452));
        service.writeChunk(7, created.sessionId(), 0, slice(archive, 0, 1024));
        service.writeChunk(7, created.sessionId(), 1, slice(archive, 1024, 1024));
        service.complete(7, created.sessionId());

        assertTrue(imported.await(10, TimeUnit.SECONDS), "导入未在超时前启动");
        assertArrayEquals(archive, received.get(), "分片必须按序号落到正确偏移");

        ProjectUploadSessionService.SessionView done = waitFor(service, 7, created.sessionId(),
                ProjectUploadSessionService.Status.DONE);
        assertEquals(11, done.projectId());
        assertEquals(3, done.fileCount());
    }

    @Test
    void refusesToCompleteWhileChunksAreMissing() throws Exception {
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", 2048, null, true);
        service.writeChunk(7, created.sessionId(), 0, payload(1024));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.complete(7, created.sessionId()));

        assertTrue(error.getMessage().contains("1 个分片未上传"), error.getMessage());
    }

    @Test
    void rejectsChunksWithTheWrongLengthSoTheFileCannotBeSilentlyShort() throws Exception {
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", 3000, null, true);

        IllegalArgumentException middle = assertThrows(IllegalArgumentException.class,
                () -> service.writeChunk(7, created.sessionId(), 0, payload(900)));
        assertTrue(middle.getMessage().contains("应为 1024 字节"), middle.getMessage());

        IllegalArgumentException last = assertThrows(IllegalArgumentException.class,
                () -> service.writeChunk(7, created.sessionId(), 2, payload(500)));
        assertTrue(last.getMessage().contains("应为 952 字节"), last.getMessage());
    }

    @Test
    void repeatedChunkUploadOverwritesInsteadOfDoubleCounting() throws Exception {
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", 2048, null, true);
        service.writeChunk(7, created.sessionId(), 0, payload(1024));
        ProjectUploadSessionService.SessionView afterRetry =
                service.writeChunk(7, created.sessionId(), 0, payload(1024));

        assertEquals(1, afterRetry.receivedChunks());
        assertEquals(1024L, afterRetry.receivedBytes());
    }

    @Test
    void sessionReportsWhichChunksArrivedSoTheClientCanResumeOnlyTheMissingOnes() throws Exception {
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", 4096, null, true);
        service.writeChunk(7, created.sessionId(), 0, payload(1024));
        service.writeChunk(7, created.sessionId(), 2, payload(1024));

        ProjectUploadSessionService.SessionView view = service.get(7, created.sessionId());

        assertEquals(List.of(0, 2), view.receivedChunkIndexes());
        assertEquals(2, view.receivedChunks());
    }

    @Test
    void rejectsArchivesAboveTheArchiveLimitBeforeAnyByteIsStored() {
        ProjectUploadSessionService service = service(limits(1024, 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create(7, "project.zip", 4L * 1024 * 1024, null, true));

        assertTrue(error.getMessage().contains("超过上限"), error.getMessage());
    }

    @Test
    void rejectsNonZipNamesAndEmptyFiles() {
        ProjectUploadSessionService service = service(limits(1024, 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create(7, "project.tar", 100, null, true)).getMessage().contains(".zip"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.create(7, "project.zip", 0, null, true)).getMessage().contains("为空"));
    }

    @Test
    void otherStudentsCannotTouchASessionEvenWithItsId() throws Exception {
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", 1024, null, true);

        assertThrows(IllegalArgumentException.class,
                () -> service.writeChunk(8, created.sessionId(), 0, payload(1024)));
        assertThrows(IllegalArgumentException.class, () -> service.get(8, created.sessionId()));
        assertThrows(IllegalArgumentException.class, () -> service.cancel(8, created.sessionId()));
    }

    @Test
    void cancellingADownloadingSessionDeletesItsStagingDirectory() throws Exception {
        ProjectUploadSessionService service = service(limits(1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) ->
                        new ProjectUploadSessionService.ImportOutcome(1, project, 0, 0L, 0, List.of()));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", 2048, null, true);
        Path directory = tempDir.resolve("staging").resolve(created.sessionId());
        assertTrue(Files.isDirectory(directory));

        ProjectUploadSessionService.SessionView cancelled = service.cancel(7, created.sessionId());

        assertEquals(ProjectUploadSessionService.Status.CANCELLED, cancelled.status());
        assertFalse(Files.exists(directory), "取消后暂存目录必须删除");
    }

    @Test
    void importProgressReachesTheSessionSoTheUiCanShowARealPercentage() throws Exception {
        Path archive = zipWithEntries(60);
        byte[] bytes = Files.readAllBytes(archive);
        ProjectUploadSessionService service = service(limits(64 * 1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) -> {
                    new ProjectArchiveExtractor(new ProjectArchiveLimits())
                            .extract(path, tempDir.resolve("extracted"), true, listener);
                    return new ProjectUploadSessionService.ImportOutcome(5, project, 60, 120L, 0, List.of());
                });

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", bytes.length, null, true);
        service.writeChunk(7, created.sessionId(), 0, bytes);
        assertEquals(60, service.complete(7, created.sessionId()).totalEntries());

        ProjectUploadSessionService.SessionView done = waitFor(service, 7, created.sessionId(),
                ProjectUploadSessionService.Status.DONE);
        assertEquals(done.totalEntries(), done.processedEntries());
    }

    @Test
    void importFailureIsReportedOnTheSessionInsteadOfHanging() throws Exception {
        ProjectUploadSessionService service = service(limits(64 * 1024, 10 * 1024 * 1024),
                (studentId, path, name, project, skip, listener) -> {
                    throw new IllegalArgumentException("项目文件数超过上限 50000 个");
                });
        byte[] bytes = Files.readAllBytes(zipWithEntries(2));

        ProjectUploadSessionService.SessionView created = service.create(7, "project.zip", bytes.length, null, true);
        service.writeChunk(7, created.sessionId(), 0, bytes);
        service.complete(7, created.sessionId());

        ProjectUploadSessionService.SessionView failed = waitFor(service, 7, created.sessionId(),
                ProjectUploadSessionService.Status.FAILED);
        assertNotNull(failed.errorMessage());
        assertTrue(failed.errorMessage().contains("文件数超过上限"), failed.errorMessage());
    }

    private ProjectUploadSessionService.SessionView waitFor(ProjectUploadSessionService service, Integer studentId,
                                                           String sessionId, ProjectUploadSessionService.Status expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        ProjectUploadSessionService.SessionView view = service.get(studentId, sessionId);
        while (view.status() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            view = service.get(studentId, sessionId);
        }
        assertEquals(expected, view.status(), "等待终态超时，实际状态 " + view.status() + " / " + view.errorMessage());
        return view;
    }

    private Path zipWithEntries(int count) throws IOException {
        Path archive = tempDir.resolve("entries-" + count + ".zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < count; index++) {
                zip.putNextEntry(new ZipEntry("src/file" + index + ".txt"));
                zip.write(("x" + index).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }
}
