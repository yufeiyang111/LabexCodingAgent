package com.labex.labexagent.workspace;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 分片上传的会话管理：建会话 → 逐片写入 → 完成触发导入。
 *
 * <p>为什么需要它：浏览器把整包一次 POST 上去时，请求体会被反向代理与 CDN 按「单请求」截断
 * （Cloudflare Free/Pro 为 100 MB），{@code maxArchiveBytes} 根本到不了应用层。切成 8 MiB
 * 的片逐片发送后，单次请求体远低于任何一层网关的限制，500 MB 的上限才是真的。
 *
 * <p>分片按 {@code index * chunkSize} 直接写入同一个预分配文件的对应偏移，不拼接、不重排，
 * 因此重复上传同一片是幂等的覆盖，任一片失败也只需重传那一片。
 *
 * <p>会话注册表为进程内内存：重启后未完成的会话丢失，前端重新开始即可；磁盘残留由基于
 * mtime 的过期清理兜底，与会话 TTL 同一个开关。
 */
@Service
public class ProjectUploadSessionService {

    public enum Status { RECEIVING, IMPORTING, DONE, FAILED, CANCELLED }

    /** 面向控制器的只读快照。{@code processedEntries} 在导入开始前为 0，{@code totalEntries} 在导入前未知。 */
    public record SessionView(String sessionId, Status status, long receivedBytes, long totalBytes,
                              int receivedChunks, int totalChunks, int processedEntries, int totalEntries,
                              int projectId, String projectName, int fileCount, long extractedSize,
                              int skippedCount, List<String> skippedSamples, String errorMessage) {
    }

    /** 导入回调：把已处理条目数与总条目数推回会话，前端据此显示真实百分比。 */
    public interface Importer {
        ImportOutcome importArchive(Integer studentId, Path archivePath, String originalFileName,
                                    String projectName, boolean skipDependencyDirectories,
                                    ProjectArchiveExtractor.ProgressListener listener) throws IOException;
    }

    public record ImportOutcome(int projectId, String projectName, int fileCount, long totalSize,
                                int skippedCount, List<String> skippedSamples) {
    }

    private static final class Session {
        final String sessionId;
        final Integer studentId;
        final String originalFileName;
        final String projectName;
        final boolean skipDependencyDirectories;
        final long totalBytes;
        final int chunkSize;
        final boolean[] received;
        final int[] chunkLengths;
        final Path directory;
        final Path archivePath;
        final long createdAt = System.currentTimeMillis();
        volatile Status status = Status.RECEIVING;
        volatile long lastActivityAt = createdAt;
        volatile int totalEntries;
        volatile int processedEntries;
        volatile int projectId;
        volatile String resultProjectName;
        volatile int fileCount;
        volatile long extractedSize;
        volatile int skippedCount;
        volatile List<String> skippedSamples = List.of();
        volatile String errorMessage;

        private Session(String sessionId, Integer studentId, String originalFileName, String projectName,
                        boolean skipDependencyDirectories, long totalBytes, int chunkSize, Path directory) {
            this.sessionId = sessionId;
            this.studentId = studentId;
            this.originalFileName = originalFileName;
            this.projectName = projectName;
            this.skipDependencyDirectories = skipDependencyDirectories;
            this.totalBytes = totalBytes;
            this.chunkSize = chunkSize;
            this.directory = directory;
            this.archivePath = directory.resolve("archive.zip");
            int count = (int) ((totalBytes + chunkSize - 1) / chunkSize);
            this.received = new boolean[count];
            this.chunkLengths = new int[count];
        }

        long receivedBytes() {
            long sum = 0;
            for (int length : chunkLengths) {
                sum += length;
            }
            return sum;
        }

        int receivedChunkCount() {
            int count = 0;
            for (boolean value : received) {
                if (value) count++;
            }
            return count;
        }

        boolean complete() {
            return receivedChunkCount() == received.length;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ProjectUploadSessionService.class);

    private final ProjectArchiveLimits limits;
    private final ProjectUploadStagingDirectory staging;
    private final ProjectArchiveExtractor extractor;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private Importer importer;

    public ProjectUploadSessionService(ProjectArchiveLimits limits, ProjectUploadStagingDirectory staging,
                                       ProjectArchiveExtractor extractor) {
        this.limits = limits;
        this.staging = staging;
        this.extractor = extractor;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "project-upload-import");
            thread.setDaemon(true);
            return thread;
        });
        purgeOrphanDirectories();
    }

    /** 由 {@code StudentProjectServiceImpl} 在启动后注入，避免 workspace 包反向依赖 service 包。 */
    public void registerImporter(Importer importer) {
        this.importer = importer;
    }

    public SessionView create(Integer studentId, String fileName, long size, String projectName,
                              boolean skipDependencyDirectories) throws IOException {
        if (studentId == null) {
            throw new IllegalArgumentException("Invalid student id");
        }
        String safeName = fileName == null ? "" : fileName.trim();
        if (!safeName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Only .zip project archives are supported currently");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("压缩包为空，请重新打包后再上传");
        }
        if (size > limits.getMaxArchiveBytes()) {
            throw new IllegalArgumentException("压缩包 " + ProjectArchiveExtractor.formatMegabytes(size)
                    + " MB，超过上限 " + ProjectArchiveExtractor.formatMegabytes(limits.getMaxArchiveBytes()) + " MB");
        }
        requireFreeDisk(size);
        purgeExpired();
        if (sessions.size() >= limits.getMaxConcurrentSessions()) {
            throw new IllegalStateException("服务器上传会话已满，请稍后重试");
        }
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        Path directory = staging.root().resolve(sessionId);
        Files.createDirectories(directory);
        Session session = new Session(sessionId, studentId, safeName, projectName,
                skipDependencyDirectories, size, (int) limits.getChunkSizeBytes(), directory);
        try (RandomAccessFile file = new RandomAccessFile(session.archivePath.toFile(), "rw")) {
            file.setLength(size);
        }
        sessions.put(sessionId, session);
        log.info("UPLOAD_SESSION_CREATED sessionId={} studentId={} bytes={} chunks={} chunkSize={}",
                sessionId, studentId, size, session.received.length, session.chunkSize);
        return view(session);
    }

    /**
     * 写入一片。重复写同一片是覆盖，不会重复计数；片大小必须与 {@code chunkSize} 一致（最后一片可短）。
     * 越界的下标与超长分片一律拒绝，避免"写进去了但永远凑不齐"。
     */
    public SessionView writeChunk(Integer studentId, String sessionId, int index, byte[] data) throws IOException {
        Session session = requireOwned(studentId, sessionId);
        synchronized (session) {
            requireStatus(session, Status.RECEIVING);
            if (index < 0 || index >= session.received.length) {
                throw new IllegalArgumentException("分片序号超出范围");
            }
            if (data == null || data.length == 0) {
                throw new IllegalArgumentException("分片内容为空");
            }
            boolean last = index == session.received.length - 1;
            int expected = last ? (int) (session.totalBytes - (long) index * session.chunkSize) : session.chunkSize;
            if (data.length != expected) {
                throw new IllegalArgumentException("分片 " + index + " 应为 " + expected + " 字节，实际 " + data.length + " 字节");
            }
            try (RandomAccessFile file = new RandomAccessFile(session.archivePath.toFile(), "rw")) {
                file.seek((long) index * session.chunkSize);
                file.write(data);
            }
            session.received[index] = true;
            session.chunkLengths[index] = data.length;
            session.lastActivityAt = System.currentTimeMillis();
        }
        return view(session);
    }

    /** 所有分片到齐后启动异步导入；导入进度靠 {@link #get} 轮询。 */
    public SessionView complete(Integer studentId, String sessionId) throws IOException {
        Session session = requireOwned(studentId, sessionId);
        synchronized (session) {
            requireStatus(session, Status.RECEIVING);
            if (!session.complete()) {
                throw new IllegalArgumentException("还有 " + (session.received.length - session.receivedChunkCount())
                        + " 个分片未上传，无法开始导入");
            }
            if (importer == null) {
                throw new IllegalStateException("上传导入服务未就绪，请稍后重试");
            }
            session.status = Status.IMPORTING;
            session.lastActivityAt = System.currentTimeMillis();
        }
        session.totalEntries = countEntriesQuietly(session.archivePath);
        executor.execute(() -> runImport(session));
        log.info("UPLOAD_SESSION_IMPORTING sessionId={} entries={}", session.sessionId, session.totalEntries);
        return view(session);
    }

    public SessionView get(Integer studentId, String sessionId) {
        return view(requireOwned(studentId, sessionId));
    }

    /** 取消：收到一半的会话直接删目录；导入中的不能撤（服务端已开始写工作区，语义参照导出）。 */
    public SessionView cancel(Integer studentId, String sessionId) {
        Session session = requireOwned(studentId, sessionId);
        synchronized (session) {
            if (session.status == Status.RECEIVING) {
                session.status = Status.CANCELLED;
                session.lastActivityAt = System.currentTimeMillis();
                deleteQuietly(session.directory);
            }
        }
        return view(session);
    }

    @Scheduled(fixedDelayString = "${labex-agent.project-archive.session-cleanup-interval-ms:60000}")
    public void purgeExpired() {
        long ttl = TimeUnit.MINUTES.toMillis(limits.getSessionTtlMinutes());
        long now = System.currentTimeMillis();
        for (Session session : sessions.values()) {
            if (session.status == Status.IMPORTING) continue;
            if (now - session.lastActivityAt < ttl) continue;
            sessions.remove(session.sessionId);
            deleteQuietly(session.directory);
            log.info("UPLOAD_SESSION_EXPIRED sessionId={} status={}", session.sessionId, session.status);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void runImport(Session session) {
        long startedAt = System.currentTimeMillis();
        try {
            ImportOutcome outcome = importer.importArchive(session.studentId, session.archivePath,
                    session.originalFileName, session.projectName, session.skipDependencyDirectories,
                    processed -> session.processedEntries = processed);
            session.projectId = outcome.projectId();
            session.resultProjectName = outcome.projectName();
            session.fileCount = outcome.fileCount();
            session.extractedSize = outcome.totalSize();
            session.skippedCount = outcome.skippedCount();
            session.skippedSamples = outcome.skippedSamples();
            session.processedEntries = session.totalEntries;
            session.status = Status.DONE;
            log.info("UPLOAD_SESSION_DONE sessionId={} projectId={} elapsedMs={}",
                    session.sessionId, outcome.projectId(), System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            session.status = Status.FAILED;
            session.errorMessage = e.getMessage() == null ? "导入失败" : e.getMessage();
            log.warn("UPLOAD_SESSION_FAILED sessionId={} reason={}", session.sessionId, session.errorMessage);
        } finally {
            session.lastActivityAt = System.currentTimeMillis();
            deleteQuietly(session.directory);
        }
    }

    private int countEntriesQuietly(Path archivePath) {
        try {
            return extractor.countEntries(archivePath);
        } catch (Exception e) {
            // 数不出条目数不该阻止导入：进度退化成不确定，导入本身照常进行。
            log.warn("UPLOAD_SESSION_ENTRY_COUNT_FAILED archive={} reason={}", archivePath, e.getMessage());
            return 0;
        }
    }

    private void requireFreeDisk(long incomingBytes) {
        try {
            Path root = staging.root();
            long usable = Files.getFileStore(root).getUsableSpace();
            long required = limits.getMinFreeDiskBytes() + incomingBytes;
            if (usable < required) {
                throw new IllegalStateException("服务器剩余磁盘空间不足，需要约 "
                        + ProjectArchiveExtractor.formatMegabytes(required) + " MB，当前可用 "
                        + ProjectArchiveExtractor.formatMegabytes(usable) + " MB");
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取暂存目录的磁盘空间：" + e.getMessage(), e);
        }
    }

    private Session requireOwned(Integer studentId, String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || !session.studentId.equals(studentId)) {
            throw new IllegalArgumentException("上传会话不存在或已过期");
        }
        return session;
    }

    private static void requireStatus(Session session, Status expected) {
        if (session.status != expected) {
            throw new IllegalStateException("上传会话状态为 " + session.status + "，无法继续");
        }
    }

    private static SessionView view(Session session) {
        return new SessionView(session.sessionId, session.status, session.receivedBytes(), session.totalBytes,
                session.receivedChunkCount(), session.received.length, session.processedEntries,
                session.totalEntries, session.projectId, session.resultProjectName, session.fileCount,
                session.extractedSize, session.skippedCount, session.skippedSamples, session.errorMessage);
    }

    private void purgeOrphanDirectories() {
        try (Stream<Path> entries = Files.list(staging.root())) {
            List<Path> stale = new ArrayList<>();
            entries.filter(Files::isDirectory).forEach(stale::add);
            stale.sort(Comparator.comparing(Path::toString));
            for (Path directory : stale) {
                deleteQuietly(directory);
            }
            if (!stale.isEmpty()) {
                log.info("UPLOAD_STAGING_PURGED_ON_STARTUP count={}", stale.size());
            }
        } catch (IOException e) {
            log.warn("UPLOAD_STAGING_PURGE_FAILED reason={}", e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // 单个文件删不掉不影响其它清理，交给下一次过期扫描。
                }
            });
        } catch (IOException ignored) {
            // 目录本就不存在或已被其它进程删除。
        }
    }
}
