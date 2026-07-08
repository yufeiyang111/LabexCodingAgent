package com.labex.labexagent.terminal;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.labex.security.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private JwtUtil jwtUtil;

    // sessionId -> TerminalSession
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    // WebSocketSession -> sessionId
    private final Map<WebSocketSession, String> sessionMapping = new ConcurrentHashMap<>();
    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> wsMapping = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
        // 验证 token（从 URL 参数获取）
        String query = wsSession.getUri() != null ? wsSession.getUri().getQuery() : null;
        String token = null;
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring(6);
                    break;
                }
            }
        }

        // 验证 token 有效性
        if (token == null || token.isEmpty() || !jwtUtil.validateToken(token)) {
            log.warn("Terminal WebSocket connection rejected: invalid or missing token");
            JsonObject error = new JsonObject();
            error.addProperty("type", "error");
            error.addProperty("message", "Authentication required");
            sendMessage(wsSession, error.toString());
            wsSession.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        String username = jwtUtil.getUsernameFromToken(token);
        String sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        sessionMapping.put(wsSession, sessionId);
        log.info("Terminal WebSocket connected: sessionId={}, user={}", sessionId, username);

        // 发送会话 ID
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "session");
        msg.addProperty("sessionId", sessionId);
        sendMessage(wsSession, msg.toString());
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
        String payload = message.getPayload();
        JsonObject msg;

        try {
            msg = gson.fromJson(payload, JsonObject.class);
        } catch (Exception e) {
            sendError(wsSession, "Invalid JSON: " + e.getMessage());
            return;
        }

        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        String sessionId = sessionMapping.get(wsSession);

        switch (type) {
            case "create" -> handleCreate(wsSession, sessionId, msg);
            case "input" -> handleInput(sessionId, msg);
            case "resize" -> handleResize(sessionId, msg);
            case "ping" -> handlePong(wsSession);
            case "close" -> handleClose(sessionId);
            default -> sendError(wsSession, "Unknown message type: " + type);
        }
    }

    /**
     * 创建新的终端会话
     */
    private void handleCreate(WebSocketSession wsSession, String sessionId, JsonObject msg) {
        // 如果已有会话，先关闭
        TerminalSession existing = sessions.get(sessionId);
        if (existing != null) {
            existing.destroy();
        }

        String cwd = msg.has("cwd") ? msg.get("cwd").getAsString() : null;
        log.info("Creating terminal session {} with cwd: {}", sessionId, cwd);

        TerminalSession terminal = new TerminalSession(sessionId, cwd);
        terminal.setOutputCallback(data -> {
            JsonObject output = new JsonObject();
            output.addProperty("type", "output");
            output.addProperty("data", data);
            try {
                sendMessage(wsSession, output.toString());
            } catch (Exception e) {
                log.debug("Failed to send output for session {}: {}", sessionId, e.getMessage());
            }
        });
        terminal.setCloseCallback(exitCode -> {
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
            terminal.start();
            sessions.put(sessionId, terminal);
            wsMapping.put(sessionId, wsSession);

            JsonObject created = new JsonObject();
            created.addProperty("type", "created");
            created.addProperty("sessionId", sessionId);
            sendMessage(wsSession, created.toString());

            log.info("Terminal session {} created successfully", sessionId);
        } catch (IOException e) {
            log.error("Failed to create terminal session {}: {}", sessionId, e.getMessage(), e);
            sendError(wsSession, "Failed to create terminal: " + e.getMessage());
        }
    }

    /**
     * 处理终端输入
     */
    private void handleInput(String sessionId, JsonObject msg) {
        if (sessionId == null) return;
        TerminalSession terminal = sessions.get(sessionId);
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
        TerminalSession terminal = sessions.get(sessionId);
        if (terminal == null) return;
        int cols = msg.has("cols") ? msg.get("cols").getAsInt() : 120;
        int rows = msg.has("rows") ? msg.get("rows").getAsInt() : 30;
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
        TerminalSession terminal = sessions.remove(sessionId);
        if (terminal != null) {
            terminal.destroy();
            log.info("Terminal session {} closed by client", sessionId);
        }
        wsMapping.remove(sessionId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status) {
        String sessionId = sessionMapping.remove(wsSession);
        if (sessionId != null) {
            TerminalSession terminal = sessions.remove(sessionId);
            if (terminal != null) {
                terminal.destroy();
                log.info("Terminal session {} destroyed (WebSocket closed)", sessionId);
            }
            wsMapping.remove(sessionId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception) {
        log.error("Terminal WebSocket transport error: {}", exception.getMessage());
        String sessionId = sessionMapping.get(wsSession);
        if (sessionId != null) {
            handleClose(sessionId);
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
}
