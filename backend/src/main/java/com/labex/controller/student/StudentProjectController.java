package com.labex.controller.student;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.common.Result;
import com.labex.controller.student.ProjectCommandSafety;
import com.labex.entity.StudentProject;
import com.labex.service.ProjectTerminalService;
import com.labex.service.StudentProjectService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
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
    @Autowired
    private StudentProjectService studentProjectService;
    @Autowired
    private ProjectTerminalService projectTerminalService;

    @GetMapping
    public Result<List<StudentProject>> list(Authentication auth) {
        Integer studentId = this.getStudentId(auth);
        List<StudentProject> projects = this.studentProjectService.list(new LambdaQueryWrapper<StudentProject>().eq(StudentProject::getStudentId, studentId).orderByDesc(StudentProject::getCreateTime));
        return Result.success(projects);
    }

    @GetMapping(value={"/{projectId}"})
    public Result<StudentProject> detail(@PathVariable Integer projectId, Authentication auth) {
        StudentProject project = this.studentProjectService.refreshProjectMetadata(this.getStudentId(auth), projectId);
        return project != null ? Result.success(project) : Result.error("Project not found");
    }

    @GetMapping(value={"/{projectId}/files"})
    public Result<Map<String, Object>> readFile(@PathVariable Integer projectId, @RequestParam String path, Authentication auth) {
        try {
            String content = this.studentProjectService.readProjectFile(this.getStudentId(auth), projectId, path);
            return Result.success(Map.of("path", path, "content", content));
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
            String command;
            StudentProject project = this.studentProjectService.getOwnedProject(this.getStudentId(auth), projectId);
            if (project == null) {
                return Result.error("Project not found");
            }
            String string = command = request != null ? request.getCommand() : null;
            if (command == null || command.isBlank()) {
                return Result.error("command is required");
            }
            ProjectCommandSafety.SafetyCheck safety = ProjectCommandSafety.check((String)command, (request != null && Boolean.TRUE.equals(request.getAllowDangerous()) ? 1 : 0) != 0);
            if (!safety.allowed()) {
                return Result.success(Map.of("command", command, "exitCode", "", "output", safety.message(), "approvalRequired", safety.approvalRequired(), "riskLevel", safety.riskLevel(), "matchedRule", safety.matchedRule()));
            }
            ProjectTerminalService.TerminalSession session = this.projectTerminalService.create(this.getStudentId(auth), project, "Terminal", request != null ? request.getPath() : null);
            int timeout = Math.min(600, Math.max(1, request != null && request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 60));
            ProjectTerminalService.TerminalRunResult result = this.projectTerminalService.run(session, project, command, request != null ? request.getPath() : null, false, timeout);
            ProjectTerminalService.TerminalSession snapSession = result.session();
            return Result.success(Map.of("command", command, "exitCode", result.exitCode() == null ? "" : result.exitCode(), "output", snapSession.snapshot()));
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @GetMapping(value={"/{projectId}/terminal/sessions"})
    public Result<List<Map<String, Object>>> listTerminalSessions(@PathVariable Integer projectId, Authentication auth) {
        Integer studentId = this.getStudentId(auth);
        List<ProjectTerminalService.TerminalSession> sessions = this.projectTerminalService.list(studentId, projectId);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (ProjectTerminalService.TerminalSession s : sessions) {
            result.add(Map.of("session", s.snapshot()));
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
            String command;
            StudentProject project = this.studentProjectService.getOwnedProject(this.getStudentId(auth), projectId);
            if (project == null) {
                return Result.error("Project not found");
            }
            ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(this.getStudentId(auth), projectId, sessionId);
            if (session == null) {
                return Result.error("Terminal session not found");
            }
            String string = command = request != null ? request.getCommand() : null;
            if (command == null || command.isBlank()) {
                return Result.error("command is required");
            }
            ProjectCommandSafety.SafetyCheck safety = ProjectCommandSafety.check((String)command, (request != null && Boolean.TRUE.equals(request.getAllowDangerous()) ? 1 : 0) != 0);
            if (!safety.allowed()) {
                return Result.success(Map.of("approvalRequired", safety.approvalRequired(), "message", safety.message(), "riskLevel", safety.riskLevel(), "matchedRule", safety.matchedRule(), "output", session.snapshot()));
            }
            int timeout = Math.min(600, Math.max(1, request != null && request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 60));
            ProjectTerminalService.TerminalRunResult result = this.projectTerminalService.run(session, project, command, request != null ? request.getPath() : null, request != null && Boolean.TRUE.equals(request.getLongRunning()), timeout);
            this.studentProjectService.refreshProjectMetadata(this.getStudentId(auth), projectId);
            Map<String, Object> info = new java.util.LinkedHashMap<>();
            info.put("approvalRequired", false);
            info.put("running", result.running());
            info.put("exitCode", result.exitCode() == null ? "" : result.exitCode());
            info.put("sessionId", session.sessionId);
            info.put("name", session.name);
            info.put("output", session.snapshot());
            return Result.success(info);
        }
        catch (Exception e) {
            return Result.error((String)e.getMessage());
        }
    }

    @GetMapping(value={"/{projectId}/terminal/sessions/{sessionId}"})
    public Result<Map<String, Object>> getTerminalSession(@PathVariable Integer projectId, @PathVariable String sessionId, Authentication auth) {
        ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(this.getStudentId(auth), projectId, sessionId);
        return session != null ? Result.success(Map.of("session", session.snapshot())) : Result.error("Terminal session not found");
    }

    @PostMapping(value={"/{projectId}/terminal/sessions/{sessionId}/stop"})
    public Result<Map<String, Object>> stopTerminalSession(@PathVariable Integer projectId, @PathVariable String sessionId, Authentication auth) {
        ProjectTerminalService.TerminalSession session = this.projectTerminalService.getOwned(this.getStudentId(auth), projectId, sessionId);
        if (session == null) {
            return Result.error("Terminal session not found");
        }
        this.projectTerminalService.stop(session);
        return Result.success(Map.of("session", session.snapshot()));
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
            Path root = Path.of(project.getWorkspacePath(), new String[0]).toAbsolutePath().normalize();
            Path dir = root.resolve(".labex").normalize();
            Files.createDirectories(dir, new FileAttribute[0]);
            StringBuilder env = new StringBuilder("# Labex cloud runtime config\n");
            if (config != null) {
                config.forEach((key, value) -> {
                    if (key != null && key.matches("[A-Za-z0-9_.-]+")) {
                        env.append(key.toUpperCase().replace('.', '_').replace('-', '_')).append('=').append(value == null ? "" : value).append('\n');
                    }
                });
            }
            Files.writeString(dir.resolve("runtime.env"), env.toString(), StandardCharsets.UTF_8, new OpenOption[0]);
            Files.writeString(dir.resolve("README.md"), "# Labex Runtime\n\n\u4e91\u7aef\u8fd0\u884c\u914d\u7f6e\u4fdd\u5b58\u5728 runtime.env\uff0c\u53ef\u88ab Agent \u548c\u7ec8\u7aef\u547d\u4ee4\u8bfb\u53d6\u3002\n", StandardCharsets.UTF_8, new OpenOption[0]);
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
    public static class TerminalRunRequest { private String command; private String path; private String sessionId; private String name; private boolean allowDangerous; private Integer timeoutSeconds; private Boolean longRunning; public String getCommand() { return command; } public void setCommand(String command) { this.command = command; } public String getPath() { return path; } public void setPath(String path) { this.path = path; } public String getSessionId() { return sessionId; } public void setSessionId(String sessionId) { this.sessionId = sessionId; } public String getName() { return name; } public void setName(String name) { this.name = name; } public boolean getAllowDangerous() { return allowDangerous; } public void setAllowDangerous(boolean allowDangerous) { this.allowDangerous = allowDangerous; } public Integer getTimeoutSeconds() { return timeoutSeconds; } public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; } public Boolean getLongRunning() { return longRunning; } public void setLongRunning(Boolean longRunning) { this.longRunning = longRunning; } }
}
