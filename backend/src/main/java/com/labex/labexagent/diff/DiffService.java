package com.labex.labexagent.diff;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.commandsecurity.AgentProjectMetadataRefreshScheduler;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.WorkspaceContextInvalidator;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.labexagent.workspace.WorkspaceLeaseService;
import com.labex.mapper.AgentFileChangeMapper;
import com.labex.service.StudentProjectService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DiffService {
    private static final Logger log = LoggerFactory.getLogger(DiffService.class);

    private final Map<String, PendingChange> pendingChanges = new ConcurrentHashMap();
    private final Map<String, PendingChange> appliedChanges = new ConcurrentHashMap();
    private final Map<String, DeferredSnapshotBatch> deferredSnapshots = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> deferredSnapshotExecutors = new ConcurrentHashMap<>();
    private final StudentProjectService studentProjectService;
    private final AgentTaskService taskService;
    private final AgentFileChangeMapper fileChangeMapper;
    private final GitSnapshotService snapshotService;
    private final WorkspaceLeaseService workspaceLeases;
    private final WorkspaceContextInvalidator contextInvalidator;
    private final ThreadLocal<ApplyTelemetry> lastApplyTelemetry = new ThreadLocal<>();
    private AgentProjectMetadataRefreshScheduler metadataRefreshScheduler;

    public DiffService(StudentProjectService studentProjectService, AgentTaskService taskService, AgentFileChangeMapper fileChangeMapper, GitSnapshotService snapshotService) {
        this(studentProjectService, taskService, fileChangeMapper, snapshotService, new WorkspaceLeaseService(),
                WorkspaceContextInvalidator.noop());
    }

    @Autowired
    public DiffService(StudentProjectService studentProjectService, AgentTaskService taskService,
                       AgentFileChangeMapper fileChangeMapper, GitSnapshotService snapshotService,
                       WorkspaceLeaseService workspaceLeases, WorkspaceContextInvalidator contextInvalidator) {
        this.studentProjectService = studentProjectService;
        this.taskService = taskService;
        this.fileChangeMapper = fileChangeMapper;
        this.snapshotService = snapshotService;
        this.workspaceLeases = workspaceLeases;
        this.contextInvalidator = contextInvalidator;
    }

    public ApplyTelemetry consumeLastApplyTelemetry() {
        ApplyTelemetry telemetry = this.lastApplyTelemetry.get();
        this.lastApplyTelemetry.remove();
        return telemetry == null ? ApplyTelemetry.empty() : telemetry;
    }

    public void clearLastApplyTelemetry() {
        this.lastApplyTelemetry.remove();
    }

    @Autowired(required = false)
    void setMetadataRefreshScheduler(AgentProjectMetadataRefreshScheduler metadataRefreshScheduler) {
        this.metadataRefreshScheduler = metadataRefreshScheduler;
    }

    public PendingChange stage(Integer studentId, StudentProject project, String relativePath, String beforeContent, String afterContent) {
        return this.stage(studentId, project, null, null, relativePath, beforeContent, afterContent);
    }

    public PendingChange stage(Integer studentId, StudentProject project, String conversationId, Long taskId, String relativePath, String beforeContent, String afterContent) {
        return this.stage(studentId, project, conversationId, taskId, relativePath, beforeContent, afterContent, beforeContent == null || beforeContent.isEmpty() ? "create" : "modify");
    }

    public PendingChange stageAndApply(Integer studentId, StudentProject project, String conversationId, Long taskId, String relativePath, String beforeContent, String afterContent) throws Exception {
        return this.stageAndApply(studentId, project, conversationId, taskId, relativePath, beforeContent, afterContent, beforeContent == null || beforeContent.isEmpty() ? "create" : "modify");
    }

    public PendingChange stageAndApply(Integer studentId, StudentProject project, String conversationId, Long taskId, String relativePath, String beforeContent, String afterContent, String changeType) throws Exception {
        return this.stageAndApplyBatch(studentId, project, conversationId, taskId,
                List.of(new ChangeRequest(relativePath, beforeContent, afterContent, changeType))).get(0);
    }

    /**
     * Agent-only entry point for the step-level snapshot coordinator. The first implementation
     * preserves existing write semantics; later coordinator slices move after tracking out of the
     * tool-return path without changing the public file-change contract.
     */
    public PendingChange stageAndApplyDeferred(Integer studentId, StudentProject project, String conversationId, Long taskId,
                                                 String relativePath, String beforeContent, String afterContent, String changeType) throws Exception {
        return this.stageAndApplyBatchDeferred(studentId, project, conversationId, taskId,
                List.of(new ChangeRequest(relativePath, beforeContent, afterContent, changeType))).get(0);
    }

    public List<PendingChange> stageAndApplyBatchDeferred(Integer studentId, StudentProject project, String conversationId,
                                                             Long taskId, List<ChangeRequest> requests) throws Exception {
        return this.stageAndApplyBatchInternal(studentId, project, conversationId, taskId, requests, true);
    }

    public List<PendingChange> stageAndApplyBatch(Integer studentId, StudentProject project, String conversationId,
                                                     Long taskId, List<ChangeRequest> requests) throws Exception {
        return this.stageAndApplyBatchInternal(studentId, project, conversationId, taskId, requests, false);
    }

    private List<PendingChange> stageAndApplyBatchInternal(Integer studentId, StudentProject project, String conversationId,
                                                             Long taskId, List<ChangeRequest> requests, boolean deferAfterSnapshot) throws Exception {
        validateBatchRequests(project, requests);
        this.clearLastApplyTelemetry();
        Map<String, Long> timingMs = new LinkedHashMap<>();
        long totalStartedNanos = System.nanoTime();
        String checkoutId = checkoutId(project);
        String owner = leaseOwner(conversationId, taskId);
        String phase = "workspace_lease";
        log.info("DIFF_APPLY_START taskId={} projectId={} owner={} checkoutId={} changeCount={}",
                taskId, project.getProjectId(), owner, checkoutId, requests.size());
        try {
            long leaseStartedNanos = System.nanoTime();
            try (WorkspaceLeaseService.Lease ignored = workspaceLeases.acquire(checkoutId, owner)) {
                log.info("DIFF_APPLY_LEASE_ACQUIRED taskId={} projectId={} waitMs={}",
                        taskId, project.getProjectId(), elapsedMs(leaseStartedNanos));
                List<PendingChange> changes = new ArrayList<>();
                List<AgentFileChange> fileChanges = new ArrayList<>();

                phase = "stage_and_persist";
                long stageStartedNanos = System.nanoTime();
                try {
                    for (ChangeRequest request : requests) {
                        PendingChange change = this.stage(studentId, project, conversationId, taskId,
                                request.relativePath(), request.beforeContent(), request.afterContent(), request.changeType());
                        AgentFileChange fileChange = this.loadFileChange(studentId, change.getId(), "pending");
                        if (fileChange == null) {
                            throw new IllegalStateException("Staged change not found");
                        }
                        changes.add(change);
                        fileChanges.add(fileChange);
                    }
                } catch (Exception e) {
                    this.markPendingBatchFailed(fileChanges, changes, e);
                    throw e;
                }
                long stageElapsedMs = elapsedMs(stageStartedNanos);
                timingMs.put("stagePersistMs", stageElapsedMs);
                log.info("DIFF_APPLY_STAGED taskId={} projectId={} changeCount={} stageMs={}",
                        taskId, project.getProjectId(), changes.size(), stageElapsedMs);

                phase = "verify_before_hash";
                long verifyStartedNanos = System.nanoTime();
                for (int index = 0; index < changes.size(); index++) {
                    this.verifyBeforeHash(fileChanges.get(index), changes.get(index),
                            this.resolveChangePath(project, changes.get(index).getRelativePath()));
                }
                long verifyElapsedMs = elapsedMs(verifyStartedNanos);
                timingMs.put("verifyBeforeHashMs", verifyElapsedMs);
                log.info("DIFF_APPLY_HASH_VERIFIED taskId={} projectId={} verifyMs={}",
                        taskId, project.getProjectId(), verifyElapsedMs);

                List<String> snapshotPaths = changes.stream().map(PendingChange::getRelativePath).toList();
                phase = "snapshot_before";
                long beforeSnapshotStartedNanos = System.nanoTime();
                GitSnapshotService.Snapshot beforeSnapshot = this.snapshotService.capture(project, "before patch batch", snapshotPaths);
                long beforeSnapshotElapsedMs = elapsedMs(beforeSnapshotStartedNanos);
                timingMs.put("snapshotBeforeMs", beforeSnapshotElapsedMs);
                log.info("DIFF_APPLY_SNAPSHOT_BEFORE taskId={} projectId={} available={} state={} elapsedMs={}",
                        taskId, project.getProjectId(), beforeSnapshot.available(), beforeSnapshot.status(), beforeSnapshotElapsedMs);

                phase = "write_files";
                long writeStartedNanos = System.nanoTime();
                try {
                    for (PendingChange change : changes) {
                        this.writeChange(project, change);
                    }
                } catch (Exception e) {
                    this.restoreBatchWrites(project, changes);
                    this.markPendingBatchFailed(fileChanges, changes, e);
                    throw e;
                }
                long writeElapsedMs = elapsedMs(writeStartedNanos);
                timingMs.put("writeFilesMs", writeElapsedMs);
                log.info("DIFF_APPLY_FILES_WRITTEN taskId={} projectId={} changeCount={} writeMs={}",
                        taskId, project.getProjectId(), changes.size(), writeElapsedMs);

                if (deferAfterSnapshot) {
                    phase = "defer_snapshot_after";
                    long deferredRecordStartedNanos = System.nanoTime();
                    this.markDeferredSnapshot(project, owner, changes, fileChanges, beforeSnapshot, snapshotPaths);
                    long deferredRecordElapsedMs = elapsedMs(deferredRecordStartedNanos);
                    timingMs.put("deferredRecordMs", deferredRecordElapsedMs);
                    long metadataRefreshStartedNanos = System.nanoTime();
                    if (this.metadataRefreshScheduler != null) {
                        this.metadataRefreshScheduler.schedule(studentId, project.getProjectId(), "agent_file_change");
                    } else {
                        this.studentProjectService.refreshProjectMetadata(studentId, project.getProjectId());
                    }
                    long metadataRefreshElapsedMs = elapsedMs(metadataRefreshStartedNanos);
                    timingMs.put("metadataRefreshDispatchMs", metadataRefreshElapsedMs);
                    long contextInvalidationStartedNanos = System.nanoTime();
                    this.contextInvalidator.invalidate(project, snapshotPaths);
                    long contextInvalidationElapsedMs = elapsedMs(contextInvalidationStartedNanos);
                    timingMs.put("contextInvalidationMs", contextInvalidationElapsedMs);
                    long totalElapsedMs = elapsedMs(totalStartedNanos);
                    timingMs.put("totalMs", totalElapsedMs);
                    this.lastApplyTelemetry.set(new ApplyTelemetry("complete", Map.copyOf(timingMs)));
                    log.info("DIFF_APPLY_DEFERRED taskId={} projectId={} changeCount={} beforeSnapshotMs={} writeMs={} deferredRecordMs={} metadataRefreshDispatchMs={} contextInvalidationMs={} totalMs={}",
                            taskId, project.getProjectId(), changes.size(), beforeSnapshotElapsedMs, writeElapsedMs,
                            deferredRecordElapsedMs, metadataRefreshElapsedMs, contextInvalidationElapsedMs, totalElapsedMs);
                    return changes;
                }

                phase = "snapshot_after";
                long afterSnapshotStartedNanos = System.nanoTime();
                GitSnapshotService.Snapshot afterSnapshot = this.snapshotService.capture(project, "after patch batch", snapshotPaths);
                long afterSnapshotElapsedMs = elapsedMs(afterSnapshotStartedNanos);
                timingMs.put("snapshotAfterMs", afterSnapshotElapsedMs);
                log.info("DIFF_APPLY_SNAPSHOT_AFTER taskId={} projectId={} available={} state={} elapsedMs={}",
                        taskId, project.getProjectId(), afterSnapshot.available(), afterSnapshot.status(), afterSnapshotElapsedMs);

                phase = "snapshot_diff";
                long snapshotDiffStartedNanos = System.nanoTime();
                List<GitSnapshotService.ChangedFile> snapshotFiles = this.snapshotService.usablePair(beforeSnapshot, afterSnapshot)
                        ? this.snapshotService.changedFiles(project, beforeSnapshot, afterSnapshot) : List.of();
                long snapshotDiffElapsedMs = elapsedMs(snapshotDiffStartedNanos);
                timingMs.put("snapshotDiffMs", snapshotDiffElapsedMs);
                log.info("DIFF_APPLY_SNAPSHOT_DIFF taskId={} projectId={} changedFiles={} elapsedMs={}",
                        taskId, project.getProjectId(), snapshotFiles.size(), snapshotDiffElapsedMs);

                phase = "complete_change_records";
                long completeStartedNanos = System.nanoTime();
                for (int index = 0; index < changes.size(); index++) {
                    this.completeAppliedChange(project, changes.get(index), fileChanges.get(index), beforeSnapshot, afterSnapshot,
                            snapshotFiles);
                }
                long completeElapsedMs = elapsedMs(completeStartedNanos);
                timingMs.put("changeRecordCompletionMs", completeElapsedMs);
                log.info("DIFF_APPLY_CHANGE_RECORDS_COMPLETE taskId={} projectId={} elapsedMs={}",
                        taskId, project.getProjectId(), completeElapsedMs);

                phase = "refresh_and_invalidate";
                long refreshStartedNanos = System.nanoTime();
                this.studentProjectService.refreshProjectMetadata(studentId, project.getProjectId());
                this.contextInvalidator.invalidate(project, changes.stream().map(PendingChange::getRelativePath).toList());
                long refreshElapsedMs = elapsedMs(refreshStartedNanos);
                timingMs.put("refreshAndInvalidateMs", refreshElapsedMs);
                long totalElapsedMs = elapsedMs(totalStartedNanos);
                timingMs.put("totalMs", totalElapsedMs);
                this.lastApplyTelemetry.set(new ApplyTelemetry("complete", Map.copyOf(timingMs)));
                log.info("DIFF_APPLY_COMPLETE taskId={} projectId={} totalMs={} stageMs={} verifyMs={} beforeSnapshotMs={} writeMs={} afterSnapshotMs={} snapshotDiffMs={} recordsMs={} refreshMs={}",
                        taskId, project.getProjectId(), totalElapsedMs, stageElapsedMs, verifyElapsedMs,
                        beforeSnapshotElapsedMs, writeElapsedMs, afterSnapshotElapsedMs, snapshotDiffElapsedMs,
                        completeElapsedMs, refreshElapsedMs);
                // Applying a file batch is one step of an active Agent run, not completion of the run itself.
                return changes;
            }
        } catch (Exception failure) {
            long totalElapsedMs = elapsedMs(totalStartedNanos);
            timingMs.put("totalMs", totalElapsedMs);
            this.lastApplyTelemetry.set(new ApplyTelemetry(phase, Map.copyOf(timingMs)));
            log.warn("DIFF_APPLY_FAILED taskId={} projectId={} phase={} elapsedMs={} errorType={} error={}",
                    taskId, project.getProjectId(), phase, totalElapsedMs,
                    failure.getClass().getSimpleName(), failure.getMessage());
            throw failure;
        }
    }

    public PendingChange stage(Integer studentId, StudentProject project, String conversationId, Long taskId, String relativePath, String beforeContent, String afterContent, String changeType) {
        String safeRelativePath = safeRelativePath(project, relativePath);
        String id = UUID.randomUUID().toString();
        String diff = this.unifiedDiff(safeRelativePath, beforeContent, afterContent);
        AgentChangeSet changeSet = this.taskService.getOrCreateOpenChangeSet(studentId, project.getProjectId(), conversationId, taskId);
        AgentFileChange fileChange = new AgentFileChange();
        fileChange.setChangeId(id);
        fileChange.setChangeSetId(changeSet.getChangeSetId());
        fileChange.setTaskId(taskId);
        fileChange.setConversationId(conversationId);
        fileChange.setStudentId(studentId);
        fileChange.setProjectId(project.getProjectId());
        fileChange.setRelativePath(safeRelativePath);
        fileChange.setChangeType(changeType == null || changeType.isBlank() ? "modify" : changeType);
        fileChange.setBeforeHash(this.sha256(beforeContent));
        fileChange.setBeforeContent(beforeContent == null ? "" : beforeContent);
        fileChange.setAfterContent(afterContent == null ? "" : afterContent);
        fileChange.setDiff(diff);
        fileChange.setStatus("pending");
        fileChange.setCreateTime(LocalDateTime.now());
        fileChange.setUpdateTime(LocalDateTime.now());
        this.fileChangeMapper.insert(fileChange);
        this.taskService.incrementChangeCount(changeSet.getChangeSetId());
        this.taskService.updateTask(taskId, "running", "\u8bb0\u5f55\u81ea\u52a8\u5e94\u7528\u53d8\u66f4", "\u5df2\u751f\u6210 diff\uff0c\u51c6\u5907\u81ea\u52a8\u5e94\u7528");
        PendingChange change = this.toPendingChange(fileChange);
        this.pendingChanges.put(id, change);
        return change;
    }

    private void validateBatchRequests(StudentProject project, List<ChangeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one change is required");
        }
        Set<String> paths = new HashSet<>();
        for (ChangeRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("Change is required");
            }
            String relativePath = safeRelativePath(project, request.relativePath());
            if (!paths.add(relativePath)) {
                throw new IllegalArgumentException("A patch batch cannot modify the same file twice: " + relativePath);
            }
        }
    }

    private void writeChange(StudentProject project, PendingChange change) throws Exception {
        Path file = this.resolveChangePath(project, change.getRelativePath());
        if ("delete".equalsIgnoreCase(change.getChangeType())) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent(), new FileAttribute[0]);
        Files.writeString(file, change.getAfterContent(), StandardCharsets.UTF_8, new OpenOption[0]);
    }

    private void restoreBatchWrites(StudentProject project, List<PendingChange> changes) {
        for (int index = changes.size() - 1; index >= 0; index--) {
            PendingChange change = changes.get(index);
            try {
                Path file = this.resolveChangePath(project, change.getRelativePath());
                if ("create".equalsIgnoreCase(change.getChangeType())) {
                    Files.deleteIfExists(file);
                } else {
                    Files.createDirectories(file.getParent(), new FileAttribute[0]);
                    Files.writeString(file, change.getBeforeContent(), StandardCharsets.UTF_8, new OpenOption[0]);
                }
            } catch (Exception ignored) {
                // The original write failure is more useful to the caller than a best-effort rollback failure.
            }
        }
    }

    private void completeAppliedChange(StudentProject project, PendingChange change, AgentFileChange fileChange,
                                       GitSnapshotService.Snapshot beforeSnapshot,
                                       GitSnapshotService.Snapshot afterSnapshot,
                                       List<GitSnapshotService.ChangedFile> allSnapshotFiles) {
        List<GitSnapshotService.ChangedFile> snapshotFiles = allSnapshotFiles.stream()
                .filter(file -> change.getRelativePath().equals(file.path())
                        || change.getRelativePath().equals(file.oldPath()))
                .toList();
        if (snapshotFiles.isEmpty() && beforeSnapshot.available() && afterSnapshot.available()
                && !beforeSnapshot.ref().equals(afterSnapshot.ref())) {
            snapshotFiles = List.of(new GitSnapshotService.ChangedFile(change.getChangeType(), "", change.getRelativePath()));
        }
        String gitDiff = this.gitDiff(project, beforeSnapshot, afterSnapshot, snapshotFiles);
        if (!gitDiff.isBlank()) {
            change.setDiff(gitDiff);
            fileChange.setDiff(gitDiff);
        }
        this.pendingChanges.remove(change.getId());
        this.appliedChanges.put(change.getId(), change);
        String priorSnapshotStatus = fileChange.getSnapshotStatus();
        fileChange.setStatus("applied");
        fileChange.setAppliedTime(LocalDateTime.now());
        fileChange.setUpdateTime(LocalDateTime.now());
        if (beforeSnapshot.available() && afterSnapshot.available()) {
            fileChange.setSnapshotBeforeRef(beforeSnapshot.ref());
            fileChange.setSnapshotAfterRef(afterSnapshot.ref());
            fileChange.setSnapshotPaths(this.snapshotService.serializePaths(snapshotFiles));
            fileChange.setSnapshotStatus(this.snapshotService.usablePair(beforeSnapshot, afterSnapshot) ? "captured" : "clean");
        } else {
            fileChange.setSnapshotStatus("unavailable");
        }
        if ("pending_step".equals(priorSnapshotStatus)) {
            int updated = this.fileChangeMapper.update(null, new LambdaUpdateWrapper<AgentFileChange>()
                    .eq(AgentFileChange::getChangeId, fileChange.getChangeId())
                    .eq(AgentFileChange::getSnapshotStatus, "pending_step")
                    .set(AgentFileChange::getDiff, fileChange.getDiff())
                    .set(AgentFileChange::getStatus, fileChange.getStatus())
                    .set(AgentFileChange::getAppliedTime, fileChange.getAppliedTime())
                    .set(AgentFileChange::getUpdateTime, fileChange.getUpdateTime())
                    .set(AgentFileChange::getSnapshotBeforeRef, fileChange.getSnapshotBeforeRef())
                    .set(AgentFileChange::getSnapshotAfterRef, fileChange.getSnapshotAfterRef())
                    .set(AgentFileChange::getSnapshotPaths, fileChange.getSnapshotPaths())
                    .set(AgentFileChange::getSnapshotStatus, fileChange.getSnapshotStatus()));
            if (updated != 1) {
                log.warn("AGENT_STEP_SNAPSHOT_CAS_MISSED changeId={} targetStatus={}", fileChange.getChangeId(), fileChange.getSnapshotStatus());
            }
        } else {
            this.fileChangeMapper.updateById(fileChange);
        }
    }

    public void scheduleDeferredSnapshot(String changeId) {
        DeferredSnapshotBatch batch = this.deferredSnapshots.get(changeId);
        if (batch == null) {
            return;
        }
        synchronized (batch) {
            if (batch.future != null) {
                return;
            }
            batch.future = CompletableFuture.runAsync(() -> finalizeDeferredSnapshot(batch), deferredSnapshotExecutor(batch.project));
        }
    }

    public void awaitDeferredSnapshots(Long taskId, String reason) {
        java.util.LinkedHashSet<DeferredSnapshotBatch> batches = new java.util.LinkedHashSet<>(this.deferredSnapshots.values());
        for (DeferredSnapshotBatch batch : batches) {
            if (!Objects.equals(taskId, batch.taskId)) {
                continue;
            }
            this.scheduleDeferredSnapshot(batch.changeIds.get(0));
            CompletableFuture<Void> future;
            synchronized (batch) { future = batch.future; }
            if (future != null) {
                try { future.join(); } catch (Exception ignored) { }
            }
        }
        log.info("AGENT_STEP_SNAPSHOT_AWAIT taskId={} reason={} pendingBatches={}", taskId, reason, batches.size());
    }

    private void markDeferredSnapshot(StudentProject project, String owner, List<PendingChange> changes,
                                      List<AgentFileChange> fileChanges, GitSnapshotService.Snapshot beforeSnapshot,
                                      List<String> snapshotPaths) {
        for (int index = 0; index < changes.size(); index++) {
            PendingChange change = changes.get(index);
            AgentFileChange fileChange = fileChanges.get(index);
            this.pendingChanges.remove(change.getId());
            this.appliedChanges.put(change.getId(), change);
            change.setStatus("applied");
            fileChange.setStatus("applied");
            fileChange.setAppliedTime(LocalDateTime.now());
            fileChange.setUpdateTime(LocalDateTime.now());
            if (beforeSnapshot.available()) {
                fileChange.setSnapshotBeforeRef(beforeSnapshot.ref());
                fileChange.setSnapshotStatus("pending_step");
            } else {
                fileChange.setSnapshotStatus("unavailable");
            }
            this.fileChangeMapper.updateById(fileChange);
        }
        DeferredSnapshotBatch batch = new DeferredSnapshotBatch(project, owner, changes, fileChanges, beforeSnapshot,
                snapshotPaths, changes.stream().map(PendingChange::getId).toList());
        for (String changeId : batch.changeIds) {
            this.deferredSnapshots.put(changeId, batch);
        }
    }

    private void finalizeDeferredSnapshot(DeferredSnapshotBatch batch) {
        try (WorkspaceLeaseService.Lease ignored = workspaceLeases.acquire(checkoutId(batch.project), batch.owner)) {
            GitSnapshotService.Snapshot after = snapshotService.capture(batch.project, "after agent step", batch.paths);
            List<GitSnapshotService.ChangedFile> files = snapshotService.usablePair(batch.before, after)
                    ? snapshotService.changedFiles(batch.project, batch.before, after) : List.of();
            for (int index = 0; index < batch.changes.size(); index++) {
                completeAppliedChange(batch.project, batch.changes.get(index), batch.fileChanges.get(index), batch.before, after, files);
            }
            log.info("AGENT_STEP_SNAPSHOT_FINALIZED taskId={} projectId={} changeCount={} available={}",
                    batch.taskId, batch.project.getProjectId(), batch.changes.size(), after.available());
        } catch (Exception e) {
            for (AgentFileChange fileChange : batch.fileChanges) {
                fileChange.setSnapshotStatus("failed");
                fileChange.setUpdateTime(LocalDateTime.now());
                fileChangeMapper.updateById(fileChange);
            }
            log.warn("AGENT_STEP_SNAPSHOT_FAILED taskId={} projectId={} errorType={} error={}", batch.taskId,
                    batch.project.getProjectId(), e.getClass().getSimpleName(), e.getMessage());
        } finally {
            for (String changeId : batch.changeIds) deferredSnapshots.remove(changeId, batch);
        }
    }

    @PostConstruct
    void recoverInterruptedDeferredSnapshots() {
        List<AgentFileChange> interrupted = this.fileChangeMapper.selectList(new LambdaQueryWrapper<AgentFileChange>()
                .eq(AgentFileChange::getSnapshotStatus, "pending_step"));
        for (AgentFileChange fileChange : interrupted) {
            fileChange.setSnapshotStatus("recovered_content");
            fileChange.setUpdateTime(LocalDateTime.now());
            this.fileChangeMapper.update(null, new LambdaUpdateWrapper<AgentFileChange>()
                    .eq(AgentFileChange::getChangeId, fileChange.getChangeId())
                    .eq(AgentFileChange::getSnapshotStatus, "pending_step")
                    .set(AgentFileChange::getSnapshotStatus, "recovered_content")
                    .set(AgentFileChange::getUpdateTime, fileChange.getUpdateTime()));
        }
        if (!interrupted.isEmpty()) {
            log.warn("AGENT_STEP_SNAPSHOT_RECOVERED_CONTENT count={}", interrupted.size());
        }
    }

    private ExecutorService deferredSnapshotExecutor(StudentProject project) {
        String key = checkoutId(project);
        return this.deferredSnapshotExecutors.computeIfAbsent(key, ignored -> Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "labex-deferred-snapshot-" + project.getProjectId());
            thread.setDaemon(true);
            return thread;
        }));
    }

    @PreDestroy
    void shutdownDeferredSnapshotExecutors() {
        this.deferredSnapshotExecutors.values().forEach(ExecutorService::shutdownNow);
        this.deferredSnapshotExecutors.clear();
    }

    private void markPendingBatchFailed(List<AgentFileChange> fileChanges, List<PendingChange> changes, Exception error) {
        String reason = error.getMessage() == null ? "batch apply failed" : error.getMessage();
        for (int index = 0; index < fileChanges.size(); index++) {
            AgentFileChange fileChange = fileChanges.get(index);
            PendingChange change = changes.get(index);
            if ("conflicted".equals(fileChange.getStatus())) {
                continue;
            }
            fileChange.setStatus("failed");
            fileChange.setUpdateTime(LocalDateTime.now());
            this.fileChangeMapper.updateById(fileChange);
            change.setStatus("failed");
            this.pendingChanges.remove(change.getId());
        }
        if (!changes.isEmpty()) {
            this.taskService.updateTask(changes.get(0).getTaskId(), "failed", "修改应用失败", reason);
        }
    }

    public PendingChange apply(Integer studentId, String changeId) throws Exception {
        AgentFileChange fileChange = this.loadFileChange(studentId, changeId, "pending");
        PendingChange change = fileChange == null ? null : this.toPendingChange(fileChange);
        if (change == null || !change.getStudentId().equals(studentId)) {
            throw new IllegalArgumentException("Change not found");
        }
        StudentProject project = this.studentProjectService.getOwnedProject(studentId, change.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        try (WorkspaceLeaseService.Lease ignored = workspaceLeases.acquire(
                checkoutId(project), "manual:" + changeId)) {
            return this.applyWithinLease(studentId, changeId, project);
        }
    }

    private PendingChange applyWithinLease(Integer studentId, String changeId, StudentProject project) throws Exception {
        AgentFileChange fileChange = this.loadFileChange(studentId, changeId, "pending");
        PendingChange change = fileChange == null ? null : this.toPendingChange(fileChange);
        if (change == null || !change.getStudentId().equals(studentId)) {
            throw new IllegalArgumentException("Change not found");
        }
        Path file = this.resolveChangePath(project, change.getRelativePath());
        this.verifyBeforeHash(fileChange, change, file);
        List<String> snapshotPaths = List.of(change.getRelativePath());
        GitSnapshotService.Snapshot beforeSnapshot = this.snapshotService.capture(project,
                "before " + change.getRelativePath(), snapshotPaths);
        if ("delete".equalsIgnoreCase(change.getChangeType())) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent(), new FileAttribute[0]);
            Files.writeString(file, change.getAfterContent(), StandardCharsets.UTF_8, new OpenOption[0]);
        }
        GitSnapshotService.Snapshot afterSnapshot = this.snapshotService.capture(project,
                "after " + change.getRelativePath(), snapshotPaths);
        List<GitSnapshotService.ChangedFile> snapshotFiles = this.snapshotService.usablePair(beforeSnapshot, afterSnapshot)
                ? this.snapshotService.changedFiles(project, beforeSnapshot, afterSnapshot).stream()
                    .filter(f -> change.getRelativePath().equals(f.path()) || change.getRelativePath().equals(f.oldPath()))
                    .toList()
                : List.of();
        if (snapshotFiles.isEmpty() && beforeSnapshot.available() && afterSnapshot.available() && !beforeSnapshot.ref().equals(afterSnapshot.ref())) {
            snapshotFiles = List.of(new GitSnapshotService.ChangedFile(change.getChangeType(), "", change.getRelativePath()));
        }
        String gitDiff = this.gitDiff(project, beforeSnapshot, afterSnapshot, snapshotFiles);
        if (!gitDiff.isBlank()) {
            change.setDiff(gitDiff);
            fileChange.setDiff(gitDiff);
        }
        this.studentProjectService.refreshProjectMetadata(studentId, change.getProjectId());
        this.contextInvalidator.invalidate(project, List.of(change.getRelativePath()));
        this.pendingChanges.remove(changeId);
        this.appliedChanges.put(changeId, change);
        fileChange.setStatus("applied");
        fileChange.setAppliedTime(LocalDateTime.now());
        fileChange.setUpdateTime(LocalDateTime.now());
        if (beforeSnapshot.available() && afterSnapshot.available()) {
            fileChange.setSnapshotBeforeRef(beforeSnapshot.ref());
            fileChange.setSnapshotAfterRef(afterSnapshot.ref());
            fileChange.setSnapshotPaths(this.snapshotService.serializePaths(snapshotFiles));
            fileChange.setSnapshotStatus(this.snapshotService.usablePair(beforeSnapshot, afterSnapshot) ? "captured" : "clean");
        } else {
            fileChange.setSnapshotStatus("unavailable");
        }
        this.fileChangeMapper.updateById(fileChange);
        this.taskService.updateTask(change.getTaskId(), "completed", "\u4fee\u6539\u5df2\u81ea\u52a8\u5e94\u7528", "Agent \u5df2\u81ea\u52a8\u5e94\u7528\u4fee\u6539\uff0c\u53ef\u5728 Changes \u64a4\u9500");
        return change;
    }

    public void reject(Integer studentId, String changeId) {
        PendingChange change = this.loadChange(studentId, changeId, "pending");
        if (change == null || !change.getStudentId().equals(studentId)) {
            throw new IllegalArgumentException("Change not found");
        }
        this.pendingChanges.remove(changeId);
        this.fileChangeMapper.update(null, (((new LambdaUpdateWrapper<AgentFileChange>().eq(AgentFileChange::getChangeId, changeId)).set(AgentFileChange::getStatus, "rejected")).set(AgentFileChange::getRejectedTime, LocalDateTime.now())).set(AgentFileChange::getUpdateTime, LocalDateTime.now()));
        this.taskService.updateTask(change.getTaskId(), "cancelled", "\u4fee\u6539\u5df2\u62d2\u7edd", "\u7528\u6237\u62d2\u7edd\u4e86\u4fee\u6539");
    }

    public PendingChange undo(Integer studentId, String changeId) throws Exception {
        AgentFileChange fileChange = this.loadFileChange(studentId, changeId, "applied");
        PendingChange change = fileChange == null ? null : this.toPendingChange(fileChange);
        if (change == null || !change.getStudentId().equals(studentId)) {
            throw new IllegalArgumentException("Applied change not found");
        }
        StudentProject project = this.studentProjectService.getOwnedProject(studentId, change.getProjectId());
        if (project == null) {
            throw new IllegalArgumentException("Project not found");
        }
        Path file = this.resolveChangePath(project, change.getRelativePath());
        boolean restoredBySnapshot = fileChange != null
                && fileChange.getSnapshotBeforeRef() != null
                && fileChange.getSnapshotPaths() != null
                && this.snapshotService.restore(project, fileChange.getSnapshotBeforeRef(), fileChange.getSnapshotPaths());
        if (!restoredBySnapshot) {
            if ("create".equalsIgnoreCase(change.getChangeType())) {
                Files.deleteIfExists(file);
            } else {
                Files.createDirectories(file.getParent(), new FileAttribute[0]);
                Files.writeString(file, change.getBeforeContent(), StandardCharsets.UTF_8, new OpenOption[0]);
            }
        }
        this.studentProjectService.refreshProjectMetadata(studentId, change.getProjectId());
        this.contextInvalidator.invalidate(project, List.of(change.getRelativePath()));
        this.appliedChanges.remove(changeId);
        this.fileChangeMapper.update(null, (((new LambdaUpdateWrapper<AgentFileChange>().eq(AgentFileChange::getChangeId, changeId)).set(AgentFileChange::getStatus, "undone")).set(AgentFileChange::getUndoneTime, LocalDateTime.now())).set(AgentFileChange::getUpdateTime, LocalDateTime.now()));
        this.taskService.updateTask(change.getTaskId(), "cancelled", "\u4fee\u6539\u5df2\u64a4\u9500", "\u7528\u6237\u64a4\u9500\u4e86\u5df2\u5e94\u7528\u4fee\u6539");
        return change;
    }

    public List<PendingChange> recordSnapshotDiff(Integer studentId, StudentProject project, String conversationId, Long taskId, String source, GitSnapshotService.Snapshot beforeSnapshot, GitSnapshotService.Snapshot afterSnapshot) {
        if (!this.snapshotService.usablePair(beforeSnapshot, afterSnapshot)) {
            return List.of();
        }
        List<GitSnapshotService.ChangedFile> changedFiles = this.snapshotService.changedFiles(project, beforeSnapshot, afterSnapshot);
        if (changedFiles.isEmpty()) {
            return List.of();
        }
        AgentChangeSet changeSet = this.taskService.getOrCreateOpenChangeSet(studentId, project.getProjectId(), conversationId, taskId);
        List<PendingChange> recorded = new ArrayList<>();
        for (GitSnapshotService.ChangedFile changedFile : changedFiles) {
            if (recorded.size() >= 200) {
                break;
            }
            String relativePath;
            try {
                relativePath = this.safeRelativePath(project, changedFile.path());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String id = UUID.randomUUID().toString();
            String type = snapshotChangeType(changedFile.status());
            String beforeContent = this.snapshotService.readTextAt(project, beforeSnapshot.ref(), changedFile.oldPath() == null || changedFile.oldPath().isBlank() ? changedFile.path() : changedFile.oldPath());
            String afterContent = this.snapshotService.readTextAt(project, afterSnapshot.ref(), changedFile.path());
            AgentFileChange fileChange = new AgentFileChange();
            fileChange.setChangeId(id);
            fileChange.setChangeSetId(changeSet.getChangeSetId());
            fileChange.setTaskId(taskId);
            fileChange.setConversationId(conversationId);
            fileChange.setStudentId(studentId);
            fileChange.setProjectId(project.getProjectId());
            fileChange.setRelativePath(relativePath);
            fileChange.setChangeType(type);
            fileChange.setBeforeHash(this.sha256(beforeContent));
            fileChange.setBeforeContent(beforeContent);
            fileChange.setAfterContent(afterContent);
            fileChange.setDiff(this.snapshotService.diffForFile(project, beforeSnapshot, afterSnapshot, changedFile));
            fileChange.setSnapshotBeforeRef(beforeSnapshot.ref());
            fileChange.setSnapshotAfterRef(afterSnapshot.ref());
            fileChange.setSnapshotPaths(this.snapshotService.serializePaths(List.of(changedFile)));
            fileChange.setSnapshotStatus("captured");
            fileChange.setStatus("applied");
            fileChange.setCreateTime(LocalDateTime.now());
            fileChange.setUpdateTime(LocalDateTime.now());
            fileChange.setAppliedTime(LocalDateTime.now());
            this.fileChangeMapper.insert(fileChange);
            recorded.add(this.toPendingChange(fileChange));
        }
        this.taskService.incrementChangeCount(changeSet.getChangeSetId());
        if (!recorded.isEmpty()) {
            this.taskService.updateTask(taskId, "running", "\u8bb0\u5f55\u547d\u4ee4\u53d8\u66f4", (source == null ? "tool" : source) + " modified " + recorded.size() + " file(s)");
        }
        return recorded;
    }

    public PendingChange get(Integer studentId, String changeId) {
        PendingChange change = this.loadChange(studentId, changeId, null);
        if (change == null) {
            throw new IllegalArgumentException("Change not found");
        }
        return change;
    }

    public String unifiedDiff(String path, String before, String after) {
        String[] oldLines = (before == null ? "" : before).split("\\R", -1);
        String[] newLines = (after == null ? "" : after).split("\\R", -1);
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(path).append('\n');
        diff.append("+++ b/").append(path).append('\n');
        diff.append("@@ -1,").append(oldLines.length).append(" +1,").append(newLines.length).append(" @@\n");
        int max = Math.max(oldLines.length, newLines.length);
        for (int i = 0; i < max; ++i) {
            String newLine;
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String string = newLine = i < newLines.length ? newLines[i] : null;
            if (oldLine != null && newLine != null && oldLine.equals(newLine)) {
                diff.append(' ').append(oldLine).append('\n');
                continue;
            }
            if (oldLine != null) {
                diff.append('-').append(oldLine).append('\n');
            }
            if (newLine == null) continue;
            diff.append('+').append(newLine).append('\n');
        }
        return diff.toString();
    }

    private PendingChange loadChange(Integer studentId, String changeId, String requiredStatus) {
        AgentFileChange fileChange = this.loadFileChange(studentId, changeId, requiredStatus);
        if (fileChange != null) {
            return this.toPendingChange(fileChange);
        }
        PendingChange memory = (PendingChange)this.pendingChanges.get(changeId);
        if (memory == null) {
            memory = (PendingChange)this.appliedChanges.get(changeId);
        }
        if (memory == null || !memory.getStudentId().equals(studentId)) {
            return null;
        }
        if (requiredStatus != null && memory.getStatus() != null && !requiredStatus.equals(memory.getStatus())) {
            return null;
        }
        return memory;
    }

    private AgentFileChange loadFileChange(Integer studentId, String changeId, String requiredStatus) {
        return (AgentFileChange)this.fileChangeMapper.selectOne(((new LambdaQueryWrapper<AgentFileChange>().eq(AgentFileChange::getChangeId, changeId)).eq(AgentFileChange::getStudentId, studentId)).eq(requiredStatus != null, AgentFileChange::getStatus, requiredStatus));
    }

    private PendingChange toPendingChange(AgentFileChange fileChange) {
        return new PendingChange(fileChange.getChangeId(), fileChange.getStudentId(), fileChange.getProjectId(), fileChange.getConversationId(), fileChange.getTaskId(), fileChange.getChangeSetId(), fileChange.getRelativePath(), fileChange.getChangeType(), fileChange.getBeforeContent(), fileChange.getAfterContent(), fileChange.getDiff(), fileChange.getStatus());
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

    private void verifyBeforeHash(AgentFileChange fileChange, PendingChange change, Path file) throws Exception {
        boolean exists = Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if ("create".equalsIgnoreCase(change.getChangeType()) && exists) {
            this.markConflicted(fileChange, change, "target file was created after staging");
            throw new ChangeConflictException(change.getRelativePath());
        }
        String currentContent = "";
        if (exists) {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                this.markConflicted(fileChange, change, "target is not a regular file");
                throw new ChangeConflictException(change.getRelativePath());
            }
            currentContent = Files.readString(file, StandardCharsets.UTF_8);
        }
        String expectedHash = fileChange.getBeforeHash();
        if (expectedHash == null || expectedHash.isBlank()) {
            expectedHash = this.sha256(fileChange.getBeforeContent());
        }
        if (!Objects.equals(expectedHash, this.sha256(currentContent))) {
            this.markConflicted(fileChange, change, "file hash changed after staging");
            throw new ChangeConflictException(change.getRelativePath());
        }
    }

    private void markConflicted(AgentFileChange fileChange, PendingChange change, String reason) {
        fileChange.setStatus("conflicted");
        fileChange.setUpdateTime(LocalDateTime.now());
        this.fileChangeMapper.updateById(fileChange);
        change.setStatus("conflicted");
        this.pendingChanges.put(change.getId(), change);
        this.taskService.updateTask(change.getTaskId(), "conflicted", "\u4fee\u6539\u51b2\u7a81", reason + ": " + change.getRelativePath());
    }

    private String gitDiff(StudentProject project, GitSnapshotService.Snapshot beforeSnapshot,
                           GitSnapshotService.Snapshot afterSnapshot,
                           List<GitSnapshotService.ChangedFile> snapshotFiles) {
        if (!this.snapshotService.usablePair(beforeSnapshot, afterSnapshot) || snapshotFiles == null) {
            return "";
        }
        return snapshotFiles.stream()
                .map(file -> this.snapshotService.diffForFile(project, beforeSnapshot, afterSnapshot, file))
                .filter(diff -> diff != null && !diff.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private String checkoutId(StudentProject project) {
        SecureWorkspacePath paths = workspacePaths(project);
        return workspaceLeases.checkoutId(project.getProjectId(), paths.workspaceRoot());
    }

    private String leaseOwner(String conversationId, Long taskId) {
        if (taskId != null) {
            return "task:" + taskId;
        }
        if (conversationId != null && !conversationId.isBlank()) {
            return "conversation:" + conversationId;
        }
        return "mutation:" + UUID.randomUUID();
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static final class DeferredSnapshotBatch {
        private final StudentProject project;
        private final String owner;
        private final List<PendingChange> changes;
        private final List<AgentFileChange> fileChanges;
        private final GitSnapshotService.Snapshot before;
        private final List<String> paths;
        private final List<String> changeIds;
        private final Long taskId;
        private CompletableFuture<Void> future;

        private DeferredSnapshotBatch(StudentProject project, String owner, List<PendingChange> changes,
                                      List<AgentFileChange> fileChanges, GitSnapshotService.Snapshot before,
                                      List<String> paths, List<String> changeIds) {
            this.project = project;
            this.owner = owner;
            this.changes = List.copyOf(changes);
            this.fileChanges = List.copyOf(fileChanges);
            this.before = before;
            this.paths = List.copyOf(paths);
            this.changeIds = List.copyOf(changeIds);
            this.taskId = changes.isEmpty() ? null : changes.get(0).getTaskId();
        }
    }

    public record ApplyTelemetry(String phase, Map<String, Long> timingMs) {
        static ApplyTelemetry empty() {
            return new ApplyTelemetry("none", Map.of());
        }
    }

    public record ChangeRequest(String relativePath, String beforeContent, String afterContent, String changeType) {
    }

    public static final class ChangeConflictException extends IllegalStateException {
        public ChangeConflictException(String relativePath) {
            super("Change conflict: file changed after staging: " + relativePath);
        }
    }

    private String snapshotChangeType(String status) {
        String s = status == null ? "" : status.toUpperCase();
        if (s.startsWith("A")) {
            return "create";
        }
        if (s.startsWith("D")) {
            return "delete";
        }
        if (s.startsWith("R")) {
            return "rename";
        }
        return "modify";
    }

    private Path resolveChangePath(StudentProject project, String relativePath) {
        SecureWorkspacePath paths = workspacePaths(project);
        Path target = paths.resolveForCreate(relativePath);
        if (target.equals(paths.workspaceRoot())) {
            throw new IllegalArgumentException("Unsafe file path");
        }
        return target;
    }

    private String safeRelativePath(StudentProject project, String relativePath) {
        SecureWorkspacePath paths = workspacePaths(project);
        Path target = resolveChangePath(project, relativePath);
        return paths.workspaceRoot().relativize(target).toString().replace('\\', '/');
    }

    private SecureWorkspacePath workspacePaths(StudentProject project) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            throw new IllegalArgumentException("Project workspace is unavailable");
        }
        return new SecureWorkspacePath(Path.of(project.getWorkspacePath()));
    }
}
