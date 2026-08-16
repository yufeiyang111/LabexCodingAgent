package com.labex.controller.student;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.common.Result;
import com.labex.entity.CommandApproval;
import com.labex.entity.StudentProject;
import com.labex.labexagent.commandsecurity.CommandApprovalService;
import com.labex.labexagent.commandsecurity.CommandClassification;
import com.labex.labexagent.commandsecurity.CommandClassifier;
import com.labex.labexagent.commandsecurity.CommandDecision;
import com.labex.labexagent.commandsecurity.CommandRequest;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.execution.WorkerShellExecutor;
import com.labex.labexagent.runtime.AgentExecutionProperties;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.labexagent.workspace.ProjectWorkspace;
import com.labex.labexagent.workspace.SecureWorkspacePath;
import com.labex.service.ProjectTerminalService;
import com.labex.service.StudentProjectService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(value={"/student/projects"})
public class StudentProjectController {
    private static final Logger log = LoggerFactory.getLogger(StudentProjectController.class);
    private static final String TERMINAL_SOURCE = "terminal_rest";
    private static final String TERMINAL_CONVERSATION = "terminal";
    private static final long TERMINAL_TASK_ID = 0L;
    private static final String TERMINAL_PROFILE = "managed-terminal";
    private static final int APPROVAL_TTL_MINUTES = 10;
    @Autowired
    private StudentProjectService studentProjectService;
    @Autowired
    private ProjectTerminalService projectTerminalService;
    @Autowired
    private CommandApprovalService commandApprovalService;
    @Autowired
    private SandboxWorker sandboxWorker;
    @Autowired
    private AgentExecutionProperties executionProperties;
    private final CommandClassifier commandClassifier = new CommandClassifier();

    @GetMapping
    public Result<List<StudentProject>> list(Authentication auth) {
        Integer studentId = this.getStudentId(auth);
        List<StudentProject> projects = this.studentProjectService.list(new LambdaQueryWrapper<StudentProject>().eq(StudentProject::getStudentId, studentId).orderByDesc(StudentProject::getCreateTime));
        return Result.success(projects);
    }

    @GetMapping(value={"/{projectId}"})
    public Result<StudentProject> detail(@PathVariable Integer projectId, Authentication auth) {
        StudentProject project = this.studentProjectService.getOwnedProject(this.getStudentId(auth), projectId);
        return project != null ? Result.success(project) : Result.error("Project not found");
    }

    @GetMapping(value={"/{projectId}/files"})
    public Result<Map<String, Object>> readFile(@PathVariable Integer projectId, @RequestParam String path, Authentication auth) {
        try {
            return Result.success(this.studentProjectService.readProjectFileForEditor(this.getStudentId(auth), projectId, path));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PutMapping(value={"/{projectId}/files"})
    public Result<StudentProject> saveFile(@PathVariable Integer projectId, @RequestParam String path, @RequestBody FileSaveRequest request, Authentication auth) {
        try {
            StudentProject project = this.studentProjectService.saveProjectFile(this.getStudentId(auth), projectId, path, request != null ? request.getContent() : "");
            return Result.success(project);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/{projectId}/files/item"})
    public Result<StudentProject> createFileItem(@PathVariable Integer projectId, @RequestBody FileCreateRequest request, Authentication auth) {
        try {
            if (request == null) {
                return Result.error("Request body is required");
            }
            StudentProject project = this.studentProjectService.createProjectItem(this.getStudentId(auth), projectId, request.getParentPath(), request.getName(), request.getType());
            return Result.success(project);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @DeleteMapping(value={"/{projectId}/files/item"})
    public Result<StudentProject> deleteFileItem(@PathVariable Integer projectId, @RequestParam String path, Authentication auth) {
        try {
            StudentProject project = this.studentProjectService.deleteProjectItem(this.getStudentId(auth), projectId, path);
            return Result.success(project);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PutMapping(value={"/{projectId}/files/item/rename"})
    public Result<StudentProject> renameFileItem(@PathVariable Integer projectId, @RequestBody FileRenameRequest request, Authentication auth) {
        try {
            if (request == null) {
                return Result.error("Request body is required");
            }
            StudentProject project = this.studentProjectService.renameProjectItem(this.getStudentId(auth), projectId, request.getPath(), request.getNewName());
            return Result.success(project);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/{projectId}/agent/ask"})
    public Result<Map<String, Object>> askAgent(@PathVariable Integer projectId, @RequestBody AgentAskRequest request, Authentication auth) {
        try {
            String answer = this.studentProjectService.askProjectAgent(this.getStudentId(auth), projectId, request != null ? request.getPath() : null, request != null ? request.getQuestion() : null, request != null ? request.getMode() : null);
            return Result.success(Map.of("answer", answer));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/upload"})
    public Result<StudentProject> upload(@RequestParam(value="file") MultipartFile file, @RequestParam(required=false) String projectName, Authentication auth) {
        try {
            StudentProject project = this.studentProjectService.uploadProject(this.getStudentId(auth), file, projectName);
            return Result.success(project);
        }
        catch (Exception e) {
            log.error("Student project upload failed: {}", e.getMessage());
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/template"})
    public Result<StudentProject> createWithTemplate(@RequestParam String name, @RequestParam String templateKey, Authentication auth) {
        try {
            StudentProject project = this.studentProjectService.createProject(this.getStudentId(auth), name, templateKey);
            return Result.success(project);
        } catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/empty"})
    public Result<StudentProject> createEmpty(@RequestBody Map<String, String> request, Authentication auth) {
        try {
            String projectName = request != null ? request.get("projectName") : null;
            String template = request != null ? request.get("template") : null;
            return Result.success(this.studentProjectService.createProject(this.getStudentId(auth), projectName, template));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/{projectId}/terminal/run"})
    public Result<Map<String, Object>> runTerminal(@PathVariable Integer projectId, @RequestBody TerminalRunRequest request, Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            StudentProject project = this.studentProjectService.getOwnedProject(studentId, projectId);
            if (project == null) {
                return Result.error("Project not found");
            }
            String command = request != null ? request.getCommand() : null;
            if (command == null || command.isBlank()) {
                return Result.error("command is required");
            }
            ProjectTerminalService.TerminalSession session = this.projectTerminalService.create(studentId, project, "Terminal", request != null ? request.getPath() : null);
            int timeout = terminalTimeout(request != null ? request.getTimeoutSeconds() : null);
            return authorizeOrRunTerminal(studentId, project, session, command, request != null ? request.getPath() : null, false, timeout);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping(value={"/{projectId}/terminal/sessions"})
    public Result<List<Map<String, Object>>> listTerminalSessions(@PathVariable Integer projectId, Authentication auth) {
        Integer studentId = this.getStudentId(auth);
        List<ProjectTerminalService.TerminalSession> sessions = this.projectTerminalService.list(studentId, projectId);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (ProjectTerminalService.TerminalSession s : sessions) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("session", s.snapshot());
            if (s.lastExecution != null) {
                item.put("status", s.lastExecution.status());
                item.put("shell", s.lastExecution.shell());
                item.put("workdir", s.lastExecution.workdir());
                item.put("exitCode", s.lastExecution.exitCode() == null ? "" : s.lastExecution.exitCode());
            }
            result.add(item);
        }
        return Result.success(result);
    }

    @PostMapping(value={"/{projectId}/terminal/sessions"})
    public Result<Map<String, Object>> createTerminalSession(@PathVariable Integer projectId, @RequestBody TerminalRunRequest request, Authentication auth) {
        try {
            StudentProject project = this.studentProjectService.getOwnedProject(this.getStudentId(auth), projectId);
            if (project == null) {
                return Result.error("Project not found");
            }
            ProjectTerminalService.TerminalSession session = this.projectTerminalService.create(this.getStudentId(auth), project, request != null ? request.getName() : null, request != null ? request.getPath() : null);
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("sessionId", session.sessionId);
            info.put("name", session.name);
            info.put("cwd", session.cwd);
            info.put("output", session.snapshot());
            return Result.success(info);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PostMapping(value={"/{projectId}/terminal/sessions/{sessionId}/run"})
    public Result<Map<String, Object>> runTerminalSession(@PathVariable Integer projectId, @PathVariable String sessionId, @RequestBody TerminalRunRequest request, Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            StudentProject project = this.studentProjectService.getOwnedProject(studentId, projectId);
            if (project == null) {
                return Result.error("Project not found");
            }
            ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(studentId, projectId, sessionId);
            if (session == null) {
                return Result.error("Terminal session not found");
            }
            String command = request != null ? request.getCommand() : null;
            if (command == null || command.isBlank()) {
                return Result.error("command is required");
            }
            int timeout = terminalTimeout(request != null ? request.getTimeoutSeconds() : null);
            return authorizeOrRunTerminal(studentId, project, session, command, request != null ? request.getPath() : null,
                    request != null && Boolean.TRUE.equals(request.getLongRunning()), timeout);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping(value={"/{projectId}/terminal/approvals/{approvalId}/decision"})
    public Result<Map<String, Object>> decideTerminalApproval(@PathVariable Integer projectId, @PathVariable String approvalId,
                                                               @RequestBody TerminalApprovalDecisionRequest request,
                                                               Authentication auth) {
        try {
            if (request == null || request.getAction() == null || request.getDecisionIdempotencyKey() == null
                    || request.getDecisionIdempotencyKey().isBlank()) {
                return Result.error("action and decisionIdempotencyKey are required");
            }
            boolean approve;
            if ("approve".equalsIgnoreCase(request.getAction())) {
                approve = true;
            } else if ("reject".equalsIgnoreCase(request.getAction())) {
                approve = false;
            } else {
                return Result.error("invalid approval action");
            }
            CommandApproval approval = commandApprovalService.decide(getStudentId(auth), projectId, approvalId,
                    approve, request.getDecisionIdempotencyKey());
            return Result.success(Map.of("approvalId", approval.getApprovalId(), "status", approval.getStatus()));
        } catch (IllegalArgumentException e) {
            return Result.error("Command approval not found");
        } catch (Exception e) {
            return Result.error("Command approval is unavailable");
        }
    }

    @PostMapping(value={"/{projectId}/terminal/approvals/{approvalId}/execute"})
    public Result<Map<String, Object>> executeTerminalApproval(@PathVariable Integer projectId, @PathVariable String approvalId,
                                                                Authentication auth) {
        try {
            Integer studentId = getStudentId(auth);
            StudentProject project = studentProjectService.getOwnedProject(studentId, projectId);
            CommandApproval approval = commandApprovalService.findOwned(studentId, projectId, approvalId);
            if (project == null || approval == null || !TERMINAL_SOURCE.equals(approval.getSource())) {
                return unavailableApproval();
            }
            ProjectTerminalService.TerminalSession session = projectTerminalService.getOwned(studentId, projectId,
                    approval.getSessionId());
            if (session == null || !consumeTerminalApproval(approval)) {
                return unavailableApproval();
            }
            ProjectTerminalService.TerminalRunResult result = projectTerminalService.run(session, project,
                    approval.getCanonicalCommand(), approval.getWorkingDirectory(), terminalLongRunning(approval.getCommandOptions()),
                    terminalTimeout(approval.getCommandOptions()));
            studentProjectService.refreshProjectMetadataAsync(studentId, projectId, "terminal_command");
            return Result.success(terminalResult(session, result));
        } catch (Exception e) {
            return unavailableApproval();
        }
    }

    private Result<Map<String, Object>> authorizeOrRunTerminal(Integer studentId, StudentProject project,
                                                                 ProjectTerminalService.TerminalSession session,
                                                                 String command, String path, boolean longRunning,
                                                                 int timeout) throws Exception {
        String workingDirectory = terminalWorkingDirectory(project, session, path);
        WorkerRunSpec workerRun = WorkerRunSpec.forWorkspace("terminal-" + session.sessionId,
                ProjectWorkspace.paths(project).workspaceRoot(), executionProperties.isNetworkDefaultEnabled());
        WorkerShellDescriptor descriptor = new WorkerShellExecutor(sandboxWorker).descriptor(workerRun);
        String shell = executionProperties.isSafeProfile() ? "direct" : descriptor.shellName();
        CommandClassification classification = commandClassifier.classify(new CommandRequest(command, shell,
                workingDirectory, timeout, longRunning, false,
                executionProperties.getPermissionProfile()));
        if (classification.decision() == CommandDecision.BLOCK) {
            return Result.success(Map.of("refused", true, "reasonCode", classification.reasonCode().name(),
                    "riskLevel", classification.riskClass().name(), "output", "Command blocked by policy"));
        }
        if (classification.requiresApproval()) {
            CommandApproval approval = createTerminalApproval(studentId, project.getProjectId(), session.sessionId,
                    classification, shell, timeout, longRunning);
            return Result.success(Map.of("approvalRequired", true, "approvalId", approval.getApprovalId(),
                    "expiresTime", approval.getExpiresTime().toString(), "displayCommand", approval.getDisplayCommand(),
                    "riskLevel", classification.riskClass().name()));
        }
        ProjectTerminalService.TerminalRunResult result = projectTerminalService.run(session, project,
                classification.normalizedCommand().canonicalCommand(), classification.normalizedCommand().canonicalWorkingDirectory(),
                longRunning, timeout);
        studentProjectService.refreshProjectMetadataAsync(studentId, project.getProjectId(), "terminal_command");
        return Result.success(terminalResult(session, result));
    }

    private CommandApproval createTerminalApproval(Integer studentId, Integer projectId, String sessionId,
                                                     CommandClassification classification, String shell,
                                                     int timeout, boolean longRunning) {
        String invocationId = UUID.randomUUID().toString();
        return commandApprovalService.createOrGet(new CommandApprovalService.CreateRequest(
                UUID.randomUUID().toString(), invocationId, studentId, projectId, TERMINAL_TASK_ID,
                TERMINAL_CONVERSATION, sessionId, TERMINAL_SOURCE, invocationId, UUID.randomUUID().toString(),
                classification.normalizedCommand().digest(), classification.normalizedCommand().canonicalCommand(),
                classification.normalizedCommand().displayCommand(), classification.normalizedCommand().canonicalWorkingDirectory(),
                shell, terminalOptions(timeout, longRunning), classification.decision().name(),
                classification.policyVersion(), LocalDateTime.now().plusMinutes(APPROVAL_TTL_MINUTES)));
    }

    private boolean consumeTerminalApproval(CommandApproval approval) {
        return commandApprovalService.consume(new CommandApprovalService.ConsumeRequest(approval.getApprovalId(),
                approval.getStudentId(), approval.getProjectId(), approval.getTaskId(), approval.getConversationId(),
                approval.getSessionId(), approval.getSource(), approval.getInvocationId(), approval.getToolCallId(),
                approval.getCommandDigest(), approval.getCanonicalCommand(), approval.getWorkingDirectory(), approval.getShell(),
                approval.getCommandOptions(), approval.getClassification(), approval.getPolicyVersion(), approval.getExpiresTime()));
    }

    private Map<String, Object> terminalResult(ProjectTerminalService.TerminalSession session,
                                               ProjectTerminalService.TerminalRunResult result) {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("approvalRequired", false);
        info.put("running", result.running());
        info.put("exitCode", result.exitCode() == null ? "" : result.exitCode());
        info.put("sessionId", session.sessionId);
        info.put("name", session.name);
        info.put("output", session.snapshot());
        ProjectTerminalService.TerminalExecution execution = result.execution();
        if (execution != null) {
            info.put("status", execution.status());
            info.put("shell", execution.shell());
            info.put("workdir", execution.workdir());
            info.put("durationMs", execution.durationMs());
            info.put("truncated", execution.truncated());
            info.put("outputChars", execution.outputChars());
            info.put("timeoutSeconds", execution.timeoutSeconds());
        }
        return info;
    }

    private String terminalWorkingDirectory(StudentProject project, ProjectTerminalService.TerminalSession session, String path) {
        Path root = ProjectWorkspace.paths(project).workspaceRoot();
        Path cwd = path == null || path.isBlank() ? Path.of(session.cwd) : ProjectWorkspace.paths(project).resolveExisting(path);
        if (!cwd.toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("Unsafe path");
        }
        String relative = root.relativize(cwd.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private int terminalTimeout(Integer timeout) {
        return Math.min(600, Math.max(1, timeout == null ? 60 : timeout));
    }

    private String terminalOptions(int timeout, boolean longRunning) {
        return "timeout=" + timeout + ";longRunning=" + longRunning;
    }

    private int terminalTimeout(String options) {
        try {
            return terminalTimeout(Integer.parseInt(options.split(";", 2)[0].substring("timeout=".length())));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid approval options");
        }
    }

    private boolean terminalLongRunning(String options) {
        return options.endsWith(";longRunning=true");
    }

    private Result<Map<String, Object>> unavailableApproval() {
        return Result.success(Map.of("approvalUnavailable", true, "output", "Command approval is unavailable"));
    }

    @GetMapping(value={"/{projectId}/terminal/sessions/{sessionId}"})
    public Result<Map<String, Object>> getTerminalSession(@PathVariable Integer projectId, @PathVariable String sessionId, Authentication auth) {
        ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(this.getStudentId(auth), projectId, sessionId);
        if (session == null) {
            return Result.error("Terminal session not found");
        }
        return Result.success(terminalSessionInfo(session));
    }

    private Map<String, Object> terminalSessionInfo(ProjectTerminalService.TerminalSession session) {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("session", session.snapshot());
        if (session.lastExecution != null) {
            info.put("status", session.lastExecution.status());
            info.put("shell", session.lastExecution.shell());
            info.put("workdir", session.lastExecution.workdir());
            info.put("exitCode", session.lastExecution.exitCode() == null ? "" : session.lastExecution.exitCode());
            info.put("durationMs", session.lastExecution.durationMs());
            info.put("truncated", session.lastExecution.truncated());
            info.put("outputChars", session.lastExecution.outputChars());
            info.put("timeoutSeconds", session.lastExecution.timeoutSeconds());
        }
        return info;
    }

    @PostMapping(value={"/{projectId}/terminal/sessions/{sessionId}/stop"})
    public Result<Map<String, Object>> stopTerminalSession(@PathVariable Integer projectId, @PathVariable String sessionId, Authentication auth) {
        ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(this.getStudentId(auth), projectId, sessionId);
        if (session == null) {
            return Result.error("Terminal session not found");
        }
        this.projectTerminalService.stop(session);
        return Result.success(terminalSessionInfo(session));
    }

    @DeleteMapping(value={"/{projectId}/terminal/sessions/{sessionId}"})
    public Result<Void> deleteTerminalSession(@PathVariable Integer projectId, @PathVariable String sessionId, Authentication auth) {
        ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(this.getStudentId(auth), projectId, sessionId);
        if (session == null) {
            return Result.error("Terminal session not found");
        }
        this.projectTerminalService.remove(session);
        return Result.success(null);
    }

    @PostMapping(value={"/{projectId}/runtime/config"})
    public Result<StudentProject> saveRuntimeConfig(@PathVariable Integer projectId, @RequestBody Map<String, String> config, Authentication auth) {
        try {
            StudentProject project = this.studentProjectService.getOwnedProject(this.getStudentId(auth), projectId);
            if (project == null) {
                return Result.error("Project not found");
            }
            SecureWorkspacePath workspace = ProjectWorkspace.paths(project);
            Path runtimeEnv = workspace.resolveForCreate(".labex/runtime.env");
            Path runtimeReadme = workspace.resolveForCreate(".labex/README.md");
            Files.createDirectories(runtimeEnv.getParent());
            StringBuilder env = new StringBuilder("# Labex cloud runtime config\n");
            if (config != null) {
                config.forEach((key, value) -> {
                    if (key != null && key.matches("[A-Za-z0-9_.-]+")) {
                        env.append(key.toUpperCase().replace('.', '_').replace('-', '_')).append('=').append(value == null ? "" : value).append('\n');
                    }
                });
            }
            Files.writeString(runtimeEnv, env.toString(), StandardCharsets.UTF_8, new OpenOption[0]);
            Files.writeString(runtimeReadme, "# Labex Runtime\n\n\u4e91\u7aef\u8fd0\u884c\u914d\u7f6e\u4fdd\u5b58\u5728 runtime.env\uff0c\u53ef\u88ab Agent \u548c\u7ec8\u7aef\u547d\u4ee4\u8bfb\u53d6\u3002\n", StandardCharsets.UTF_8, new OpenOption[0]);
            return Result.success(this.studentProjectService.refreshProjectMetadata(this.getStudentId(auth), projectId));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @GetMapping(value={"/{projectId}/tree"})
    public Result<List<Map<String, Object>>> listTree(@PathVariable Integer projectId, @RequestParam(required=false) String path, Authentication auth) {
        try {
            List<Map<String, Object>> children = this.studentProjectService.listProjectTree(this.getStudentId(auth), projectId, path);
            return Result.success(children);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @GetMapping(value={"/{projectId}/tree/page"})
    public Result<StudentProjectService.ProjectTreePage> listTreePage(@PathVariable Integer projectId,
                                                                        @RequestParam(required=false) String path,
                                                                        @RequestParam(defaultValue="0") int offset,
                                                                        @RequestParam(defaultValue="100") int limit,
                                                                        Authentication auth) {
        try {
            Integer studentId = this.getStudentId(auth);
            log.info("[DEBUG_TREE] projectId={} path={} offset={} limit={} studentId={}", projectId, path, offset, limit, studentId);
            return Result.success(this.studentProjectService.listProjectTreePage(studentId, projectId,
                    path, offset, limit));
        } catch (Exception e) {
            log.info("[DEBUG_TREE] projectId={} EXCEPTION: {}", projectId, e.getMessage());
            log.error("[DEBUG_TREE] projectId={} EXCEPTION_STACK", projectId, e);
            return Result.error((String) e.getMessage());
        }
    }

    @GetMapping(value={"/{projectId}/export"})
    public void export(@PathVariable Integer projectId, Authentication auth, HttpServletResponse response) throws IOException {
        StudentProject project = this.studentProjectService.getOwnedProject(this.getStudentId(auth), projectId);
        if (project == null) {
            response.sendError(404, "Project not found");
            return;
        }
        String fileName = (project.getProjectName() != null ? project.getProjectName() : "project") + ".zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        this.studentProjectService.exportProject(this.getStudentId(auth), projectId, (OutputStream)response.getOutputStream());
    }

    @DeleteMapping(value={"/{projectId}"})
    public Result<Void> delete(@PathVariable Integer projectId, Authentication auth) {
        try {
            this.studentProjectService.deleteOwnedProject(this.getStudentId(auth), projectId);
            return Result.success(null);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @PutMapping(value={"/{projectId}/rename"})
    public Result<StudentProject> rename(@PathVariable Integer projectId, @RequestBody Map<String, String> request, Authentication auth) {
        try {
            String newName = request != null ? request.get("name") : null;
            StudentProject project = this.studentProjectService.renameProject(this.getStudentId(auth), projectId, newName);
            return Result.success(project);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    private Integer getStudentId(Authentication auth) {
        return Integer.parseInt(auth.getName());
    }

    public static class FileSaveRequest { private String content; public String getContent() { return content; } public void setContent(String content) { this.content = content; } }
    public static class FileCreateRequest { private String parentPath; private String name; private String type; public String getParentPath() { return parentPath; } public void setParentPath(String parentPath) { this.parentPath = parentPath; } public String getName() { return name; } public void setName(String name) { this.name = name; } public String getType() { return type; } public void setType(String type) { this.type = type; } }
    public static class FileRenameRequest { private String path; private String newName; public String getPath() { return path; } public void setPath(String path) { this.path = path; } public String getNewName() { return newName; } public void setNewName(String newName) { this.newName = newName; } }
    public static class AgentAskRequest { private String path; private String question; private String conversationId; private String mode; public String getPath() { return path; } public void setPath(String path) { this.path = path; } public String getQuestion() { return question; } public void setQuestion(String question) { this.question = question; } public String getConversationId() { return conversationId; } public void setConversationId(String conversationId) { this.conversationId = conversationId; } public String getMode() { return mode; } public void setMode(String mode) { this.mode = mode; } }
    public static class TerminalRunRequest { private String command; private String path; private String sessionId; private String name; private Integer timeoutSeconds; private Boolean longRunning; public String getCommand() { return command; } public void setCommand(String command) { this.command = command; } public String getPath() { return path; } public void setPath(String path) { this.path = path; } public String getSessionId() { return sessionId; } public void setSessionId(String sessionId) { this.sessionId = sessionId; } public String getName() { return name; } public void setName(String name) { this.name = name; } public Integer getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; } public Boolean getLongRunning() { return longRunning; } public void setLongRunning(Boolean longRunning) { this.longRunning = longRunning; } }
    public static class TerminalApprovalDecisionRequest { private String action; private String decisionIdempotencyKey; public String getAction() { return action; } public void setAction(String action) { this.action = action; } public String getDecisionIdempotencyKey() { return decisionIdempotencyKey; } public void setDecisionIdempotencyKey(String decisionIdempotencyKey) { this.decisionIdempotencyKey = decisionIdempotencyKey; } }
}
