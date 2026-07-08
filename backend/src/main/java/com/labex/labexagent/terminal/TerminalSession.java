package com.labex.labexagent.terminal;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理单个终端会话（PTY 进程）
 * 支持 Windows (cmd/PowerShell) 和 Linux (bash)
 */
@Slf4j
public class TerminalSession {

    private final String sessionId;
    private Process process;
    private OutputStream processInput;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private volatile OutputCallback outputCallback;
    private volatile CloseCallback closeCallback;
    private int cols = 120;
    private int rows = 30;
    private String workingDirectory;

    public interface OutputCallback {
        void onOutput(String data);
    }

    public interface CloseCallback {
        void onClose(int exitCode);
    }

    public TerminalSession(String sessionId, String workingDirectory) {
        this.sessionId = sessionId;
        this.workingDirectory = workingDirectory;
    }

    public void setOutputCallback(OutputCallback callback) {
        this.outputCallback = callback;
    }

    public void setCloseCallback(CloseCallback callback) {
        this.closeCallback = callback;
    }

    /**
     * 启动终端进程
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            log.warn("Terminal session {} already running", sessionId);
            return;
        }

        ProcessBuilder pb = createProcessBuilder();
        pb.redirectErrorStream(true);

        // 设置环境变量
        Map<String, String> env = pb.environment();
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("COLUMNS", String.valueOf(cols));
        env.put("LINES", String.valueOf(rows));

        log.info("Starting terminal session {} with command: {} in directory: {}",
                sessionId, pb.command(), workingDirectory);
        process = pb.start();
        processInput = process.getOutputStream();
        running.set(true);

        log.info("Terminal session {} process started, PID: {}", sessionId, process.pid());

        // 读取进程输出
        executor.submit(this::readProcessOutput);

        // 监听进程退出
        executor.submit(this::waitForProcessExit);
    }

    /**
     * 根据操作系统创建进程构建器
     */
    private ProcessBuilder createProcessBuilder() {
        ProcessBuilder pb;
        String os = System.getProperty("os.name").toLowerCase();

        if (workingDirectory != null && !workingDirectory.isBlank()) {
            File dir = new File(workingDirectory);
            if (dir.exists() && dir.isDirectory()) {
                pb = new ProcessBuilder();
                pb.directory(dir);
            } else {
                pb = new ProcessBuilder();
            }
        } else {
            pb = new ProcessBuilder();
        }

        if (os.contains("win")) {
            // Windows: 优先使用 PowerShell，回退到 cmd
            String psPath = findPowerShell();
            if (psPath != null) {
                // 使用 -NoExit 保持终端打开，-Command 设置输出编码为 UTF-8
                pb.command(psPath, "-NoLogo", "-NoProfile", "-NoExit", "-Command", "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $Host.UI.RawUI.WindowTitle = 'Labex Terminal'");
            } else {
                String cmdPath = System.getenv("COMSPEC");
                if (cmdPath == null || cmdPath.isBlank()) {
                    cmdPath = "cmd.exe";
                }
                pb.command(cmdPath, "/K", "chcp 65001 >nul");
            }
        } else {
            // Linux/Mac: 使用 bash
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) {
                shell = "/bin/bash";
            }
            pb.command(shell, "--login");
        }

        return pb;
    }

    /**
     * 查找 PowerShell 路径
     */
    private String findPowerShell() {
        String[] candidates = {
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            "C:\\Windows\\SysWOW64\\WindowsPowerShell\\v1.0\\powershell.exe",
            "powershell.exe",
            "pwsh.exe"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) return path;
        }
        // 尝试在 PATH 中查找
        try {
            Process p = new ProcessBuilder("where", "pwsh.exe").start();
            if (p.waitFor() == 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                if (line != null && !line.isBlank()) return line.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 读取进程输出并发送到回调
     */
    private void readProcessOutput() {
        try (InputStream is = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while (running.get() && (len = is.read(buffer)) != -1) {
                String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                if (outputCallback != null) {
                    outputCallback.onOutput(output);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.debug("Terminal session {} output stream closed: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 等待进程退出
     */
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

    /**
     * 向终端写入数据
     */
    public void writeInput(String data) {
        if (!running.get() || processInput == null) return;
        try {
            processInput.write(data.getBytes(StandardCharsets.UTF_8));
            processInput.flush();
        } catch (IOException e) {
            log.error("Failed to write to terminal session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 调整终端大小
     */
    public void resize(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        // 注意：Java ProcessBuilder 不直接支持动态 resize
        // 但设置环境变量可以在下次启动时生效
        // 对于 Windows cmd，这已经足够了
    }

    /**
     * 终止终端会话
     */
    public synchronized void destroy() {
        running.set(false);
        if (process != null) {
            process.destroyForcibly();
        }
        executor.shutdownNow();
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isRunning() {
        return running.get();
    }
}
