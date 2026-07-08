package com.labex.labexagent.diff;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.labex.entity.AgentChangeSet;
import com.labex.entity.AgentFileChange;
import com.labex.entity.StudentProject;
import com.labex.labexagent.diff.PendingChange;
import com.labex.labexagent.service.AgentTaskService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class DiffService {
    private final Map<String, PendingChange> pendingChanges = new ConcurrentHashMap();
    private final Map<String, PendingChange> appliedChanges = new ConcurrentHashMap();
    private final StudentProjectService studentProjectService;
    private final AgentTaskService taskService;
    private final AgentFileChangeMapper fileChangeMapper;
    private final GitSnapshotService snapshotService;

    public DiffService(StudentProjectService studentProjectService, AgentTaskService taskService, AgentFileChangeMapper fileChangeMapper, GitSnapshotService snapshotService) {
        this.studentProjectService = studentProjectService;
        this.taskService = taskService;
        this.fileChangeMapper = fileChangeMapper;
        this.snapshotService = snapshotService;
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
        PendingChange change = this.stage(studentId, project, conversationId, taskId, relativePath, beforeContent, afterContent, changeType);
        this.apply(studentId, change.getId());
        return change;
    }

    public PendingChange stage(Integer studentId, StudentProject project, String conversationId, Long taskId, String relativePath, String beforeContent, String afterContent, String changeType) {
        String id = UUID.randomUUID().toString();
        String diff = this.unifiedDiff(relativePath, beforeContent, afterContent);
        AgentChangeSet changeSet = this.taskService.getOrCreateOpenChangeSet(studentId, project.getProjectId(), conversationId, taskId);
        AgentFileChange fileChange = new AgentFileChange();
        fileChange.setChangeId(id);
        fileChange.setChangeSetId(changeSet.getChangeSetId());
        fileChange.setTaskId(taskId);
        fileChange.setConversationId(conversationId);
        fileChange.setStudentId(studentId);
        fileChange.setProjectId(project.getProjectId());
        fileChange.setRelativePath(relativePath);
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
        GitSnapshotService.Snapshot beforeSnapshot = this.snapshotService.capture(project, "before " + change.getRelativePath());
        Path root = Path.of(project.getWorkspacePath(), new String[0]).toAbsolutePath().normalize();
        Path file = root.resolve(change.getRelativePath()).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe file path");
        }
        if ("delete".equalsIgnoreCase(change.getChangeType())) {
            Files.deleteIfExists(file);
        } else {
            Files.createDirectories(file.getParent(), new FileAttribute[0]);
            Files.writeString(file, change.getAfterContent(), StandardCharsets.UTF_8, new OpenOption[0]);
        }
        GitSnapshotService.Snapshot afterSnapshot = this.snapshotService.capture(project, "after " + change.getRelativePath());
        List<GitSnapshotService.ChangedFile> snapshotFiles = this.snapshotService.usablePair(beforeSnapshot, afterSnapshot)
                ? this.snapshotService.changedFiles(project, beforeSnapshot, afterSnapshot).stream()
                    .filter(f -> change.getRelativePath().equals(f.path()) || change.getRelativePath().equals(f.oldPath()))
                    .toList()
                : List.of();
        if (snapshotFiles.isEmpty() && beforeSnapshot.available() && afterSnapshot.available() && !beforeSnapshot.ref().equals(afterSnapshot.ref())) {
            snapshotFiles = List.of(new GitSnapshotService.ChangedFile(change.getChangeType(), "", change.getRelativePath()));
        }
        this.studentProjectService.refreshProjectMetadata(studentId, change.getProjectId());
        this.pendingChanges.remove(changeId);
        this.appliedChanges.put(changeId, change);
        LambdaUpdateWrapper<AgentFileChange> update = (((new LambdaUpdateWrapper<AgentFileChange>().eq(AgentFileChange::getChangeId, changeId)).set(AgentFileChange::getStatus, "applied")).set(AgentFileChange::getAppliedTime, LocalDateTime.now())).set(AgentFileChange::getUpdateTime, LocalDateTime.now());
        if (beforeSnapshot.available() && afterSnapshot.available()) {
            update.set(AgentFileChange::getSnapshotBeforeRef, beforeSnapshot.ref())
                    .set(AgentFileChange::getSnapshotAfterRef, afterSnapshot.ref())
                    .set(AgentFileChange::getSnapshotPaths, this.snapshotService.serializePaths(snapshotFiles))
                    .set(AgentFileChange::getSnapshotStatus, this.snapshotService.usablePair(beforeSnapshot, afterSnapshot) ? "captured" : "clean");
        } else {
            update.set(AgentFileChange::getSnapshotStatus, "unavailable");
        }
        this.fileChangeMapper.update(null, update);
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
        Path root = Path.of(project.getWorkspacePath(), new String[0]).toAbsolutePath().normalize();
        Path file = root.resolve(change.getRelativePath()).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe file path");
        }
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
            fileChange.setRelativePath(changedFile.path());
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
}
