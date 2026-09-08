package com.labex.labexagent.terminal;

import com.labex.security.JwtUtil;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 终端 WebSocket 握手拦截器：
 * 在 HTTP 协议升级前完成 Token 鉴权、用户身份解析与项目工作区归属校验。
 * 遵循职责分离原则，拒绝非法连接时不触发 WebSocket 协议升级，并将认证成功的工作区透传至 session attributes。
 */
@Slf4j
@Component
public class TerminalHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_WORKSPACE = "TERMINAL_WORKSPACE";
    public static final String ATTR_STUDENT_ID = "TERMINAL_STUDENT_ID";
    public static final String ATTR_PROJECT_ID = "TERMINAL_PROJECT_ID";

    private final JwtUtil jwtUtil;
    private final TerminalWorkspaceResolver workspaceResolver;

    public TerminalHandshakeInterceptor(JwtUtil jwtUtil, TerminalWorkspaceResolver workspaceResolver) {
        this.jwtUtil = jwtUtil;
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                  WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
        URI uri = request.getURI();
        Map<String, String> queryParams = extractQueryParams(uri);
        String rawToken = queryParams.get("token");
        String projectIdStr = queryParams.get("projectId");

        if (rawToken == null || rawToken.isBlank() || projectIdStr == null || projectIdStr.isBlank()) {
            log.warn("Terminal handshake rejected: missing token or projectId, uri={}", uri.getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String token = rawToken.trim();
        if (token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        } else {
            String stripped = jwtUtil.removePrefix(token);
            if (stripped != null && !stripped.isBlank()) {
                token = stripped.trim();
            }
        }

        if (!jwtUtil.validateToken(token)) {
            log.warn("Terminal handshake rejected: invalid or expired token, uri={}", uri.getPath());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Integer studentId = jwtUtil.getUserIdFromToken(token);
        Integer projectId = parsePositiveInteger(projectIdStr);
        if (studentId == null || projectId == null) {
            log.warn("Terminal handshake rejected: invalid credentials, studentId={}, projectId={}", studentId, projectId);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        TerminalWorkspaceResolver.TerminalWorkspace workspace;
        try {
            workspace = workspaceResolver.resolveWorkspace(studentId, projectId);
        } catch (IllegalArgumentException e) {
            log.warn("Terminal handshake rejected: project access denied or not found, studentId={}, projectId={}: {}",
                    studentId, projectId, e.getMessage());
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_STUDENT_ID, studentId);
        attributes.put(ATTR_PROJECT_ID, projectId);
        attributes.put(ATTR_WORKSPACE, workspace);
        log.info("Terminal handshake approved: student={}, project={}", studentId, projectId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("Terminal handshake error: {}", exception.getMessage(), exception);
        }
    }

    private Map<String, String> extractQueryParams(URI uri) {
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
}
