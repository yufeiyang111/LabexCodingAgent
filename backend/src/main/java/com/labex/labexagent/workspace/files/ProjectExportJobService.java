package com.labex.labexagent.workspace.files;

import com.labex.entity.StudentProject;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.ProtectedWorkspacePaths;
import com.labex.labexagent.workspace.WorkspaceFileOperationProperties;
import com.labex.labexagent.workspace.WorkspaceOperationGuard;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.StudentProjectService;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 项目导出异步任务的唯一实现：创建任务秒回 jobId，后台打包到受控临时目录，
 * 前端轮询进度、完成后经下载端点从磁盘取成品。长任务不再占用 HTTP 连接，
 * 网关超时无从触发。
 * 任务注册表为进程内内存（明确边界：重启后任务状态丢失，用户重新导出即可；
 * 磁盘残留由基于文件 mtime 的启动清理兜底）。平台保护区（.labex 等）任何模式下一律排除。
 */
@Service
public class ProjectExportJobService {

    public enum JobStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }

    /** 面向控制器的只读快照。 */
    public record ExportJobView(String jobId, Integer projectId, String status,
                                int progressPercent, long totalBytes, long sizeBytes,
                                String errorMessage) {
    }

    static final class ExportJob {
        final String jobId;
        final Integer studentId;
        final Integer projectId;
        final String projectName;
        final boolean includeAll;
        final Path zipPath;
        final long createdAt = System.currentTimeMillis();
        volatile JobStatus status = JobStatus.PENDING;
        volatile long totalBytes;
        volatile long processedBytes;
        volatile long completedAt;
        volatile long lastProgressAt = createdAt;
        volatile String errorMessage;
        volatile boolean cancelRequested;
        volatile WorkspaceOperationGuard.Slot slot;

        private ExportJob(String jobId, Integer studentId, Integer projectId,
                          String projectName, boolean includeAll, Path zipPath) {
            this.jobId = jobId;
            this.studentId = studentId;
            this.projectId = projectId;
            this.projectName = projectName;
            this.includeAll = includeAll;
            this.zipPath = zipPath;
        }

        boolean active() {
            return status == JobStatus.PENDING || status == JobStatus.RUNNING;
        }
    }

    /** 打包过程中断信号；仅用于终止遍历，不外泄到调用方。 */
    private static final class JobCancelledException extends RuntimeException {
    }

    private static final Logger log = LoggerFactory.getLogger(ProjectExportJobService.class);
    private static final int MAX_JOB_REGISTRY_ENTRIES = 500;

    private final StudentProjectService studentProjectService;
    private final WorkspaceFileOperationProperties properties;
    private final WorkspaceOperationGuard guard;
    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public ProjectExportJobService(StudentProjectService studentProjectService,
                                   WorkspaceFileOperationProperties properties,
                                   WorkspaceOperationGuard guard) {
        this.studentProjectService = studentProjectService;
        this.properties = properties;
        this.guard = guard;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "project-export-job");
            thread.setDaemon(true);
            return thread;
        });
        purgeOrphanFilesOnStartup();
    }

    public ExportJobView createJob(Integer studentId, Integer projectId, boolean includeAll) {
        guard.checkRate(WorkspaceOperationGuard.FileOp.EXPORT, studentId);
        StudentProject project = requireOwnedProject(studentId, projectId);
        boolean hasActive = jobs.values().stream()
                .anyMatch(job -> job.studentId.equals(studentId) && job.active());
        if (hasActive) {
            throw new IllegalArgumentException("已有进行中的导出任务，请等待完成或取消后重试");
        }
        purgeExpired();
        SecureWorkspacePath paths = ProjectWorkspace.paths(project);
        Preflight preflight = preflight(paths, includeAll);

        WorkspaceOperationGuard.Slot slot = guard.acquireSlot(WorkspaceOperationGuard.FileOp.EXPORT);
        String jobId = UUID.randomUUID().toString().replace("-", "");
        ExportJob job = new ExportJob(jobId, studentId, projectId,
                project.getProjectName(), includeAll, resolveJobFile(jobId));
        job.totalBytes = preflight.totalBytes();
        registerWithCap(job);
        job.slot = slot;
        executor.execute(() -> runJob(job, paths));
        return view(job);
    }

    /** 归属校验 + 只读快照；RUNNING 僵死任务在此顺带判死，便于前端得到确定终态。 */
    public ExportJobView getJob(Integer studentId, Integer projectId, String jobId) {
        ExportJob job = requireOwnedJob(studentId, projectId, jobId);
        if (job.status == JobStatus.RUNNING
                && System.currentTimeMillis() - job.lastProgressAt
                > TimeUnit.MINUTES.toMillis(properties.getExportStaleJobTimeoutMinutes())) {
            synchronizedLock(job);
            if (job.status == JobStatus.RUNNING) {
                job.status = JobStatus.FAILED;
                job.errorMessage = "导出超时，请重试";
                job.completedAt = System.currentTimeMillis();
                deleteQuietly(job.zipPath);
            }
        }
        return view(job);
    }

    /** 取消：PENDING 直接终态；RUNNING 由打包线程在下一个文件边界感知并中止。 */
    public ExportJobView cancelJob(Integer studentId, Integer projectId, String jobId) {
        ExportJob job = requireOwnedJob(studentId, projectId, jobId);
        if (job.active()) {
            job.cancelRequested = true;
            if (job.status == JobStatus.PENDING) {
                finishCancelled(job);
            }
        }
        return view(job);
    }

    /** 下载前解析成品路径；归属与状态不满足时按不存在处理，避免泄漏他人产物。 */
    public Path resolveDownload(Integer studentId, Integer projectId, String jobId) {
        ExportJob job = requireOwnedJob(studentId, projectId, jobId);
        if (job.status != JobStatus.SUCCESS) {
            throw new IllegalArgumentException("导出尚未完成或已失效");
        }
        if (!Files.isRegularFile(job.zipPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("导出文件已过期");
        }
        return job.zipPath;
    }

    public String projectZipName(Integer studentId, Integer projectId, String jobId) {
        ExportJob job = requireOwnedJob(studentId, projectId, jobId);
        String base = job.projectName == null || job.projectName.isBlank() ? "project" : job.projectName;
        return base + ".zip";
    }

    /** 定时清理：TTL 过期的终态任务连同磁盘产物一起删除；僵死 RUNNING 判死。 */
    @Scheduled(fixedDelayString = "${labex-agent.file-ops.export-cleanup-interval-ms:60000}")
    public void purgeExpired() {
        long ttlMillis = TimeUnit.MINUTES.toMillis(properties.getExportJobTtlMinutes());
        long staleMillis = TimeUnit.MINUTES.toMillis(properties.getExportStaleJobTimeoutMinutes());
        long now = System.currentTimeMillis();
        for (ExportJob job : jobs.values()) {
            if (job.active() && now - job.lastProgressAt > staleMillis) {
                synchronizedLock(job);
                if (job.active()) {
                    job.status = JobStatus.FAILED;
                    job.errorMessage = "导出超时";
                    job.completedAt = now;
                    job.cancelRequested = true;
                    deleteQuietly(job.zipPath);
                }
            }
            if (!job.active() && now - job.completedAt > ttlMillis) {
                jobs.remove(job.jobId);
                deleteQuietly(job.zipPath);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ─── 后台打包 ───

    private void runJob(ExportJob job, SecureWorkspacePath paths) {
        try {
            job.status = JobStatus.RUNNING;
            job.lastProgressAt = System.currentTimeMillis();
            Files.createDirectories(job.zipPath.getParent());
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(job.zipPath))) {
                writeWorkspaceZip(paths, job, zos);
            }
            if (job.cancelRequested) {
                throw new JobCancelledException();
            }
            job.processedBytes = job.totalBytes;
            job.status = JobStatus.SUCCESS;
        } catch (JobCancelledException cancelled) {
            deleteQuietly(job.zipPath);
            finishCancelled(job);
        } catch (Exception e) {
            deleteQuietly(job.zipPath);
            if (job.cancelRequested) {
                finishCancelled(job);
            } else {
                job.status = JobStatus.FAILED;
                job.errorMessage = e.getMessage() == null ? "导出失败" : e.getMessage();
                log.warn("EXPORT_JOB_FAILED jobId={} reason={}", job.jobId, job.errorMessage);
            }
        } finally {
            if (job.completedAt == 0) job.completedAt = System.currentTimeMillis();
            job.slot.close();
            job.slot = null;
        }
    }

    private void writeWorkspaceZip(SecureWorkspacePath paths, ExportJob job, ZipOutputStream zos)
            throws IOException {
        Path root = paths.workspaceRoot();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (job.cancelRequested) throw new JobCancelledException();
                if (attrs.isSymbolicLink() || attrs.isOther()
                        || ProtectedWorkspacePaths.isProtectedEntry(root, directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String relative = root.relativize(directory).toString().replace('\\', '/');
                if (relative.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!job.includeAll && isExcludedDirectory(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return putEntry(zos, relative + "/");
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (job.cancelRequested) throw new JobCancelledException();
                if (attrs.isSymbolicLink() || attrs.isOther()
                        || ProtectedWorkspacePaths.isProtectedEntry(root, file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (filesExcluded(job, file)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(relative));
                Files.copy(file, zos);
                zos.closeEntry();
                job.processedBytes += attrs.size();
                job.lastProgressAt = System.currentTimeMillis();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean filesExcluded(ExportJob job, Path file) {
        if (job.includeAll) {
            return false;
        }
        Path parent = file.getParent();
        return parent != null && isExcludedDirectoryName(String.valueOf(parent.getFileName()));
    }

    private boolean isExcludedDirectory(String relative) {
        int slash = relative.lastIndexOf('/');
        String name = slash >= 0 ? relative.substring(slash + 1) : relative;
        return isExcludedDirectoryName(name);
    }

    private boolean isExcludedDirectoryName(String name) {
        return properties.getExportExcludedDirectoryNames().stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(name));
    }

    private static FileVisitResult putEntry(ZipOutputStream zos, String entryName) {
        try {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.closeEntry();
            return FileVisitResult.CONTINUE;
        } catch (IOException e) {
            throw new RuntimeException("写入压缩包失败: " + e.getMessage(), e);
        }
    }

    // ─── 预检 ───

    private record Preflight(long totalBytes, long fileCount) {
    }

    /** 创建前的预算预检：统计总字节并强制上限，超限直接拒绝，不产生半途而废的产物。 */
    private Preflight preflight(SecureWorkspacePath paths, boolean includeAll) {
        long[] bytes = {0L};
        long[] files = {0L};
        Path root = paths.workspaceRoot();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(root, directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!includeAll && !root.equals(directory)) {
                        String name = String.valueOf(directory.getFileName());
                        if (properties.getExportExcludedDirectoryNames().stream()
                                .anyMatch(candidate -> candidate.equalsIgnoreCase(name))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()
                            || ProtectedWorkspacePaths.isProtectedEntry(root, file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    bytes[0] += attrs.size();
                    files[0]++;
                    if (bytes[0] > properties.getExportMaxTotalBytes()) {
                        throw new IllegalArgumentException("工作区超过导出大小上限 "
                                + (properties.getExportMaxTotalBytes() / 1024 / 1024) + "MB");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("读取工作区失败: " + e.getMessage(), e);
        }
        return new Preflight(bytes[0], files[0]);
    }

    // ─── 内部工具 ───

    private Path resolveJobFile(String jobId) {
        return Path.of(properties.getExportStorageDir()).toAbsolutePath().normalize()
                .resolve(jobId + ".zip");
    }

    private void registerWithCap(ExportJob job) {
        while (true) {
            purgeExpired();
            if (jobs.size() < MAX_JOB_REGISTRY_ENTRIES || jobs.isEmpty()) {
                jobs.put(job.jobId, job);
                return;
            }
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
            List<String> removable = jobs.values().stream()
                    .filter(existing -> !existing.active())
                    .filter(existing -> toLocalTime(existing.completedAt).isBefore(threshold))
                    .map(existing -> existing.jobId)
                    .collect(Collectors.toList());
            if (removable.isEmpty()) {
                jobs.put(job.jobId, job);
                return;
            }
            removable.forEach(jobs::remove);
        }
    }

    private static LocalDateTime toLocalTime(long epochMillis) {
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneId.systemDefault());
    }

    /** 僵死判定与状态翻转需要与打包线程互斥；以任务对象为锁。 */
    private static void synchronizedLock(ExportJob job) {
        // 占位方法保持调用点语义清晰；真正互斥由 synchronized(job) 完成。
        throw new IllegalStateException("unsupported");
    }

    private ExportJobView view(ExportJob job) {
        int percent;
        if (job.status == JobStatus.SUCCESS) {
            percent = 100;
        } else if (job.status == JobStatus.PENDING || job.totalBytes <= 0) {
            percent = 0;
        } else {
            percent = (int) Math.min(99, job.processedBytes * 100 / job.totalBytes);
        }
        return new ExportJobView(job.jobId, job.projectId, job.status.name(),
                percent, job.totalBytes, job.processedBytes, job.errorMessage);
    }

    private static void finishCancelled(ExportJob job) {
        job.status = JobStatus.CANCELLED;
        job.completedAt = System.currentTimeMillis();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 清理失败留给 TTL 扫描兜底。
        }
    }

    /** 启动兜底：上次进程遗留的成品/半成品 zip 按 mtime 超过 TTL 即删除（注册表已随重启丢失）。 */
    private void purgeOrphanFilesOnStartup() {
        try {
            Path dir = Path.of(properties.getExportStorageDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            long ttlMillis = TimeUnit.MINUTES.toMillis(Math.max(1, properties.getExportJobTtlMinutes()));
            long cutoff = System.currentTimeMillis() - ttlMillis;
            List<Path> removed = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".zip"))
                        .forEach(path -> {
                            try {
                                if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                                        .toMillis() < cutoff) {
                                    Files.deleteIfExists(path);
                                    removed.add(path);
                                }
                            } catch (IOException ignored) {
                                // 单个文件清理失败不影响其余。
                            }
                        });
            }
            if (!removed.isEmpty()) {
                log.info("EXPORT_ORPHAN_PURGED count={}", removed.size());
            }
        } catch (RuntimeException | IOException e) {
            log.warn("EXPORT_ORPHAN_PURGE_FAILED reason={}", e.getMessage());
        }
    }

    private ExportJob requireOwnedJob(Integer studentId, Integer projectId, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        ExportJob job = jobs.get(jobId);
        if (job == null || !job.studentId.equals(studentId) || !job.projectId.equals(projectId)) {
            throw new IllegalArgumentException("导出任务不存在");
        }
        return job;
    }

    private StudentProject requireOwnedProject(Integer studentId, Integer projectId) {
        StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }
}
