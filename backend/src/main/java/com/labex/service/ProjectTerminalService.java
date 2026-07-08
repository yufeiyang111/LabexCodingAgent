package com.labex.service;

import com.labex.entity.StudentProject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProjectTerminalService {
    private static final Logger log = LoggerFactory.getLogger(ProjectTerminalService.class);
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    public List<TerminalSession> list(Integer studentId, Integer projectId) {
        return this.sessions.values().stream()
            .filter(item -> item.studentId.equals(studentId) && item.projectId.equals(projectId))
            .sorted((a, b) -> a.createdAt.compareTo(b.createdAt))
            .toList();
    }

    public TerminalSession create(Integer studentId, StudentProject project, String name, String path) throws Exception {
        Path cwd = this.resolveCwd(project, path);
        TerminalSession session = new TerminalSession();
        session.sessionId = UUID.randomUUID().toString();
        session.studentId = studentId;
        session.projectId = project.getProjectId();
        session.name = (name == null || name.isBlank()) ? ("Terminal " + (this.list(studentId, project.getProjectId()).size() + 1)) : name;
        session.cwd = cwd.toString();
        session.createdAt = LocalDateTime.now();
        session.append("[Terminal] " + session.name + "\n[WorkDir] " + session.cwd + "\n");
        this.sessions.put(session.sessionId, session);
        return session;
    }

    public TerminalSession getOwned(Integer studentId, Integer projectId, String sessionId) {
        TerminalSession session = this.sessions.get(sessionId);
        if (session == null || !session.studentId.equals(studentId) || !session.projectId.equals(projectId)) {
            return null;
        }
        return session;
    }

    public TerminalRunResult run(TerminalSession session, StudentProject project, String command, String path, boolean longRunning, int timeoutSeconds) throws Exception {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        if (session.process != null && session.process.isAlive()) {
            throw new IllegalStateException("Another command is already running");
        }
        Path cwd = this.resolveCwd(project, (path == null || path.isBlank()) ? session.cwd : path);
        session.cwd = cwd.toString();
        session.lastCommand = command;
        session.exitCode = null;
        session.running = true;
        session.append("\n$ " + command + "\n");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = windows ? List.of("cmd.exe", "/c", command) : List.of("/bin/sh", "-lc", command);
        Process process = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        session.process = process;
        this.startOutputReader(session, process);
        if (longRunning) {
            return new TerminalRunResult(true, null, session.snapshot(), session);
        }
        int timeout = Math.min(600, Math.max(1, timeoutSeconds));
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            session.running = false;
            session.exitCode = -1;
            session.append("\n[Timeout - Force Killed]\n");
            return new TerminalRunResult(false, Integer.valueOf(-1), session.snapshot(), session);
        }
        session.running = false;
        session.exitCode = process.exitValue();
        return new TerminalRunResult(false, session.exitCode, session.snapshot(), session);
    }

    public void stop(TerminalSession session) {
        if (session.process != null && session.process.isAlive()) {
            session.process.destroy();
            try {
                if (!session.process.waitFor(3L, TimeUnit.SECONDS)) {
                    session.process.destroyForcibly();
                }
            } catch (Exception ignored) {
                session.process.destroyForcibly();
            }
        }
        session.running = false;
        session.exitCode = -1;
        session.append("\n[Stopped]\n");
    }

    public void remove(TerminalSession session) {
        this.stop(session);
        this.sessions.remove(session.sessionId);
    }

    private void startOutputReader(TerminalSession session, Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    session.append(line + "\n");
                }
            } catch (Exception e) {
                session.append("\n[terminal reader error] " + e.getMessage() + "\n");
            } finally {
                if (!process.isAlive()) {
                    session.running = false;
                    try {
                        session.exitCode = process.exitValue();
                        session.append("\nexit=" + session.exitCode + "\n");
                    } catch (Exception exitEx) {
                        log.debug("Failed to get process exit value for session {}: {}", session.sessionId, exitEx.getMessage());
                    }
                }
            }
        }, "project-terminal-reader-" + session.sessionId);
        thread.setDaemon(true);
        thread.start();
    }

    private Path resolveCwd(StudentProject project, String path) throws Exception {
        Path root = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize();
        if (path == null || path.isBlank()) {
            return root;
        }
        Path candidate = Path.of(path).isAbsolute() ? Path.of(path).toAbsolutePath().normalize() : root.resolve(path.replace('\\', '/')).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Unsafe path");
        }
        if (Files.isRegularFile(candidate, new LinkOption[0])) {
            candidate = candidate.getParent();
        }
        return candidate;
    }

    public static class TerminalSession {
        public String sessionId;
        public Integer studentId;
        public Integer projectId;
        public String name;
        public String cwd;
        public String lastCommand;
        public Integer exitCode;
        public boolean running;
        public LocalDateTime createdAt;
        public Process process;
        public List<String> output = new java.util.ArrayList<>();
        public void append(String line) { output.add(line); }
        public String snapshot() { return String.join("\n", output); }
    }

    public static class TerminalRunResult {
        private final boolean running;
        private final Integer exitCode;
        private final String output;
        private final TerminalSession session;
        TerminalRunResult(boolean running, Integer exitCode, String output) {
            this.running = running; this.exitCode = exitCode; this.output = output; this.session = null;
        }
        TerminalRunResult(boolean running, Integer exitCode, String output, TerminalSession session) {
            this.running = running; this.exitCode = exitCode; this.output = output; this.session = session;
        }
        public boolean running() { return running; }
        public Integer exitCode() { return exitCode; }
        public String output() { return output; }
        public TerminalSession session() { return session; }
    }
}
