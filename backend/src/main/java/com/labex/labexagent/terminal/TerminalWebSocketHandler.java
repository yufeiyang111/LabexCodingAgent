package com.labex.labexagent.terminal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.security.JwtUtil;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 终端处理器
 * 协议:
 *   客户端 -> 服务端:
 *     {"type":"create","cwd":"...","cols":120,"rows":30}
 *     {"type":"input","data":"ls -la\n"}
 *     {"type":"resize","cols":120,"rows":30}
 *     {"type":"ping"}
 *   服务端 -> 客户端:
 *     {"type":"output","data":"..."}
 *     {"type":"exit","code":0}
 *     {"type":"error","message":"..."}
 *     {"type":"pong"}
 */
@Slf4j
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Gson gson = new Gson();
    static final String DISABLED_POLICY_CODE = "TERMINAL_WEBSOCKET_DISABLED";
    static final String DISABLED_POLICY_MESSAGE = "Interactive terminal WebSocket is disabled by policy. Use the managed terminal instead.";

    private final JwtUtil jwtUtil;
    private final TerminalWorkspaceResolver workspaceResolver;
    private final SandboxWorker sandboxWorker;

    // sessionId -> sandbox-owned terminal
    private final Map<String, SandboxWorker.InteractiveTerminal> sessions = new ConcurrentHashMap<>();
    // WebSocketSession -> authenticated project-bound terminal connection
    private final Map<WebSocketSession, TerminalConnection> connections = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(
            JwtUtil jwtUtil, TerminalWorkspaceResolver workspaceResolver, SandboxWorker sandboxWorker) {
        this.jwtUtil = jwtUtil;
        this.workspaceResolver = workspaceResolver;
        this.sandboxWorker = sandboxWorker;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        rejectByPolicy(wsSession);
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        // This guard intentionally precedes parsing, authorization, workspace resolution,
        // terminal creation, and input forwarding so raw input cannot reach a worker.
        rejectByPolicy(wsSession);
    }

    /**
     * 创建新的终端会话
     */
    private void handleCreate(WebSocketSession wsSession, TerminalConnection connection, JsonObject msg) {
        String sessionId = connection.sessionId();
        // 如果已有会话，先关闭
        SandboxWorker.InteractiveTerminal existing = sessions.remove(sessionId);
        if (existing != null) {
            existing.terminate();
        }

        String cwd = msg.has("cwd") ? msg.get("cwd").getAsString() : "";
        Path workingDirectory;
        try {
            workingDirectory = workspaceResolver.resolveWorkingDirectory(connection.workspace(), cwd);
        } catch (IllegalArgumentException e) {
            sendError(wsSession, e.getMessage());
            return;
        }
        int cols = getInt(msg, "cols", 120);
        int rows = getInt(msg, "rows", 30);
        log.info("Creating terminal session {} for project {} with relative cwd: {}, cols: {}, rows: {}",
                sessionId, connection.workspace().projectId(), cwd, cols, rows);

        SandboxWorker.TerminalSpec terminalSpec = new SandboxWorker.TerminalSpec(
                sessionId,
                workingDirectory,
                cols,
                rows,
                data -> {
                    JsonObject output = new JsonObject();
                    output.addProperty("type", "output");
                    output.addProperty("data", data);
                    try {
                        sendMessage(wsSession, output.toString());
                    } catch (Exception e) {
                        log.debug("Failed to send output for session {}: {}", sessionId, e.getMessage());
                    }
                },
                exitCode -> {
                    JsonObject exit = new JsonObject();
                    exit.addProperty("type", "exit");
                    exit.addProperty("code", exitCode);
                    try {
                        sendMessage(wsSession, exit.toString());
                    } catch (Exception e) {
                        log.debug("Failed to send exit for session {}: {}", sessionId, e.getMessage());
                    }
                });

        try {
            SandboxWorker.InteractiveTerminal terminal = sandboxWorker.openTerminal(
                    WorkerRunSpec.forWorkspace("terminal-" + sessionId, connection.workspace().workspaceRoot()),
                    terminalSpec);
            sessions.put(sessionId, terminal);

            JsonObject created = new JsonObject();
            created.addProperty("type", "created");
            created.addProperty("sessionId", sessionId);
            sendMessage(wsSession, created.toString());

            log.info("Terminal session {} created successfully", sessionId);
        } catch (IOException | IllegalArgumentException e) {
            log.error("Failed to create terminal session {}: {}", sessionId, e.getMessage(), e);
            sendError(wsSession, "Failed to create terminal: " + e.getMessage());
        }
    }

    /**
     * 处理终端输入
     */
    private void handleInput(String sessionId, JsonObject msg) {
        if (sessionId == null) return;
        SandboxWorker.InteractiveTerminal terminal = sessions.get(sessionId);
        if (terminal == null) {
            return;
        }
        String data = msg.has("data") ? msg.get("data").getAsString() : "";
        terminal.writeInput(data);
    }

    /**
     * 处理终端大小调整
     */
    private void handleResize(String sessionId, JsonObject msg) {
        if (sessionId == null) return;
        SandboxWorker.InteractiveTerminal terminal = sessions.get(sessionId);
        if (terminal == null) return;
        int cols = getInt(msg, "cols", 120);
        int rows = getInt(msg, "rows", 30);
        terminal.resize(cols, rows);
    }

    /**
     * 处理 pong 响应
     */
    private void handlePong(WebSocketSession wsSession) {
        JsonObject pong = new JsonObject();
        pong.addProperty("type", "pong");
        try {
            sendMessage(wsSession, pong.toString());
        } catch (Exception ignored) {}
    }

    /**
     * 关闭终端会话
     */
    private void handleClose(String sessionId) {
        if (sessionId == null) return;
        SandboxWorker.InteractiveTerminal terminal = sessions.remove(sessionId);
        if (terminal != null) {
            terminal.terminate();
            log.info("Terminal session {} closed by client", sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        TerminalConnection connection = connections.remove(wsSession);
        if (connection != null) {
            SandboxWorker.InteractiveTerminal terminal = sessions.remove(connection.sessionId());
            if (terminal != null) {
                terminal.terminate();
                log.info("Terminal session {} destroyed (WebSocket closed)", connection.sessionId());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        log.error("Terminal WebSocket transport error: {}", exception.getMessage());
        TerminalConnection connection = connections.get(wsSession);
        if (connection != null) {
            handleClose(connection.sessionId());
        }
    }

    private void sendMessage(WebSocketSession wsSession, String message) throws IOException {
        if (wsSession.isOpen()) {
            wsSession.sendMessage(new TextMessage(message));
        }
    }

    private void sendError(WebSocketSession wsSession, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        try {
            sendMessage(wsSession, error.toString());
        } catch (Exception ignored) {}
    }

    private void rejectByPolicy(WebSocketSession wsSession) {
        if (!wsSession.isOpen()) {
            return;
        }
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("code", DISABLED_POLICY_CODE);
        error.addProperty("message", DISABLED_POLICY_MESSAGE);
        try {
            sendMessage(wsSession, error.toString());
            wsSession.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // Connection is already closed.
        }
    }

    private void rejectConnection(WebSocketSession wsSession, String message) {
        sendError(wsSession, message);
        try {
            wsSession.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (IOException ignored) {
            // Connection is already closed.
        }
    }

    private Map<String, String> queryParameters(URI uri) {
        Map<String, String> parameters = new HashMap<>();
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return parameters;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            parameters.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private Integer parsePositiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int getInt(JsonObject msg, String property, int fallback) {
        if (!msg.has(property) || msg.get(property).isJsonNull()) {
            return fallback;
        }
        try {
            return msg.get(property).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private record TerminalConnection(String sessionId, TerminalWorkspaceResolver.TerminalWorkspace workspace) {
    }
}
