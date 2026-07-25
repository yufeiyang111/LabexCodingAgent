package com.labex.labexagent.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理单个真实 PTY 终端会话。
 */
@Slf4j
public class TerminalSession {

    private static final int DEFAULT_COLS = 120;
    private static final int DEFAULT_ROWS = 30;
    private static final int MIN_COLS = 20;
    private static final int MAX_COLS = 500;
    private static final int MIN_ROWS = 5;
    private static final int MAX_ROWS = 200;

    private static final String POWERSHELL_INIT =
            "[Console]::InputEncoding = [System.Text.Encoding]::UTF8; " +
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
            "$OutputEncoding = [System.Text.Encoding]::UTF8; " +
            "function global:prompt { $esc=[char]27; " +
            "$esc + '[36mPS ' + $executionContext.SessionState.Path.CurrentLocation.Path + " +
            "$esc + '[0m' + [Environment]::NewLine + $esc + '[32m> ' + $esc + '[0m' }";

    private final String sessionId;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Process process;
    private PtyProcess ptyProcess;
    private OutputStream processInput;
    private volatile OutputCallback outputCallback;
    private volatile CloseCallback closeCallback;
    private volatile int cols = DEFAULT_COLS;
    private volatile int rows = DEFAULT_ROWS;
    private final String workingDirectory;
    private final List<String> commandOverride;
    private final Map<String, String> environmentOverride;
    private volatile boolean usingPty = false;

    public interface OutputCallback {
        void onOutput(String data);
    }

    public interface CloseCallback {
        void onClose(int exitCode);
    }

    public TerminalSession(String sessionId, String workingDirectory) {
        this(sessionId, workingDirectory, DEFAULT_COLS, DEFAULT_ROWS);
    }

    public TerminalSession(String sessionId, String workingDirectory, int cols, int rows) {
        this(sessionId, workingDirectory, cols, rows, null, null);
    }

    public TerminalSession(
            String sessionId,
            String workingDirectory,
            int cols,
            int rows,
            List<String> commandOverride,
            Map<String, String> environmentOverride) {
        this.sessionId = sessionId;
        this.workingDirectory = workingDirectory;
        this.commandOverride = commandOverride == null ? null : List.copyOf(commandOverride);
        this.environmentOverride = environmentOverride == null ? null : Map.copyOf(environmentOverride);
        setSize(cols, rows);
    }

    public void setOutputCallback(OutputCallback callback) {
        this.outputCallback = callback;
    }

    public void setCloseCallback(CloseCallback callback) {
        this.closeCallback = callback;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            log.warn("Terminal session {} already running", sessionId);
            return;
        }

        try {
            process = startPtyProcess();
            usingPty = true;
        } catch (Exception e) {
            log.warn("PTY start failed for session {}, falling back to pipe process: {}", sessionId, e.getMessage());
            process = startPipeProcess();
            usingPty = false;
        }

        processInput = process.getOutputStream();
        running.set(true);

        if (!usingPty) {
            emitSystemMessage("\r\n\u001b[33m[Terminal] PTY unavailable, using compatibility mode. Some line editing may be limited.\u001b[0m\r\n");
        }

        log.info("Terminal session {} process started, PID: {}, pty: {}", sessionId, process.pid(), usingPty);
        executor.submit(this::readProcessOutput);
        executor.submit(this::waitForProcessExit);
    }

    private Process startPtyProcess() throws IOException {
        String[] command = createCommand();
        File dir = validWorkingDirectory();
        PtyProcessBuilder builder = new PtyProcessBuilder(command)
                .setEnvironment(createTerminalEnvironment())
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setRedirectErrorStream(true)
                .setWindowsAnsiColorEnabled(true);

        if (dir != null) {
            builder.setDirectory(dir.getAbsolutePath());
        }
        if (isWindows()) {
            builder.setUseWinConPty(true);
            builder.setConsole(false);
        }

        log.info("Starting PTY terminal session {} with command: {} in directory: {}",
                sessionId, String.join(" ", command), dir == null ? "(default)" : dir.getAbsolutePath());
        PtyProcess started = builder.start();
        ptyProcess = started;
        return started;
    }

    private Process startPipeProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(createCommand());
        File dir = validWorkingDirectory();
        if (dir != null) {
            pb.directory(dir);
        }
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(createTerminalEnvironment());
        log.info("Starting pipe terminal session {} with command: {} in directory: {}",
                sessionId, pb.command(), dir == null ? "(default)" : dir.getAbsolutePath());
        return pb.start();
    }

    private Map<String, String> createTerminalEnvironment() {
        if (environmentOverride != null) {
            return new HashMap<>(environmentOverride);
        }
        Path workspace = Path.of(workingDirectory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(TerminalEnvironment.runtimeRoot(workspace));
            Files.createDirectories(workspace.resolve(".labex-agent").resolve("terminal-tmp"));
        } catch (IOException e) {
            log.debug("Unable to prepare terminal temporary directory: {}", e.getMessage());
        }
        return TerminalEnvironment.forWorkspace(workspace, cols, rows, System.getenv());
    }

    private String[] createShellCommand() {
        if (isWindows()) {
            String psPath = findPowerShell();
            if (psPath != null) {
                return new String[]{ psPath, "-NoLogo", "-NoExit", "-Command", POWERSHELL_INIT };
            }
            String cmdPath = System.getenv("COMSPEC");
            if (cmdPath == null || cmdPath.isBlank()) {
                cmdPath = "cmd.exe";
            }
            return new String[]{ cmdPath, "/K", "chcp 65001 >nul && prompt $E[36m$P$E[0m$_$E[32m$G$E[0m$S" };
        }

        Path bash = Path.of("/bin/bash");
        if (Files.isExecutable(bash)) {
            return new String[]{ bash.toString(), "--noprofile", "--norc", "-i" };
        }
        return new String[]{ "/bin/sh", "-i" };
    }

    private String[] createCommand() {
        if (commandOverride != null && !commandOverride.isEmpty()) {
            return commandOverride.toArray(String[]::new);
        }
        return createShellCommand();
    }

    private File validWorkingDirectory() {
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            File dir = new File(workingDirectory);
            if (dir.exists() && dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String findPowerShell() {
        String pwsh = findOnPath("pwsh.exe");
        if (pwsh != null) {
            return pwsh;
        }
        String[] candidates = {
                "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "C:\\Windows\\SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                return path;
            }
        }
        return findOnPath("powershell.exe");
    }

    private String findOnPath(String command) {
        try {
            Process p = new ProcessBuilder("where", command).start();
            if (p.waitFor() == 0) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null && !line.isBlank()) {
                        return line.trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // Use the next candidate.
        }
        return null;
    }

    private void readProcessOutput() {
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while (running.get() && (len = is.read(buffer)) != -1) {
                String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                emitSystemMessage(output);
            }
        } catch (IOException e) {
            if (running.get()) {
                log.debug("Terminal session {} output stream closed: {}", sessionId, e.getMessage());
            }
        }
    }

    private void waitForProcessExit() {
        try {
            int exitCode = process.waitFor();
            running.set(false);
            log.info("Terminal session {} exited with code {}", sessionId, exitCode);
            if (closeCallback != null) {
                closeCallback.onClose(exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void writeInput(String data) {
        if (!running.get() || processInput == null) return;
        try {
            processInput.write(data.getBytes(StandardCharsets.UTF_8));
            processInput.flush();
        } catch (IOException e) {
            log.error("Failed to write to terminal session {}: {}", sessionId, e.getMessage());
        }
    }

    public void resize(int cols, int rows) {
        setSize(cols, rows);
        if (ptyProcess != null && running.get()) {
            try {
                ptyProcess.setWinSize(new WinSize(this.cols, this.rows));
            } catch (Exception e) {
                log.debug("Failed to resize terminal session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    public synchronized void destroy() {
        running.set(false);
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        executor.shutdownNow();
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void emitSystemMessage(String message) {
        if (outputCallback != null) {
            outputCallback.onOutput(message);
        }
    }

    private void setSize(int cols, int rows) {
        this.cols = clamp(cols, MIN_COLS, MAX_COLS, DEFAULT_COLS);
        this.rows = clamp(rows, MIN_ROWS, MAX_ROWS, DEFAULT_ROWS);
    }

    private int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(max, Math.max(min, value));
    }
}
