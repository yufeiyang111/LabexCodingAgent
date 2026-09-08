package com.labex.labexagent.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.security.JwtUtil;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class TerminalHandshakeInterceptorTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final TerminalWorkspaceResolver workspaceResolver = mock(TerminalWorkspaceResolver.class);
    private final ServerHttpRequest request = mock(ServerHttpRequest.class);
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);
    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);

    private TerminalHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(jwtUtil.removePrefix(any())).thenAnswer(invocation -> invocation.getArgument(0));
        interceptor = new TerminalHandshakeInterceptor(jwtUtil, workspaceResolver);
    }

    @Test
    void rejectsHandshakeWhenTokenOrProjectIdMissing() throws Exception {
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/api/ws/terminal"));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
        verifyNoInteractions(jwtUtil, workspaceResolver);
    }

    @Test
    void rejectsHandshakeWhenTokenInvalid() throws Exception {
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/api/ws/terminal?token=bad&projectId=1"));
        when(jwtUtil.validateToken("bad")).thenReturn(false);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        assertThat(attributes).isEmpty();
        verifyNoInteractions(workspaceResolver);
    }

    @Test
    void rejectsHandshakeWhenProjectAccessDenied() throws Exception {
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/api/ws/terminal?token=valid&projectId=2"));
        when(jwtUtil.validateToken("valid")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid")).thenReturn(10);
        when(workspaceResolver.resolveWorkspace(10, 2))
                .thenThrow(new IllegalArgumentException("Project not found or access denied"));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        assertThat(attributes).isEmpty();
    }

    @Test
    void approvesHandshakeAndPopulatesAttributes() throws Exception {
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/api/ws/terminal?token=Bearer%20valid-token&projectId=100"));
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(42);

        Path rootPath = Path.of("D:/test-workspace");
        TerminalWorkspaceResolver.TerminalWorkspace workspace =
                new TerminalWorkspaceResolver.TerminalWorkspace(42, 100, rootPath);
        when(workspaceResolver.resolveWorkspace(42, 100)).thenReturn(workspace);

        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes.get(TerminalHandshakeInterceptor.ATTR_STUDENT_ID)).isEqualTo(42);
        assertThat(attributes.get(TerminalHandshakeInterceptor.ATTR_PROJECT_ID)).isEqualTo(100);
        assertThat(attributes.get(TerminalHandshakeInterceptor.ATTR_WORKSPACE)).isEqualTo(workspace);
    }
}
