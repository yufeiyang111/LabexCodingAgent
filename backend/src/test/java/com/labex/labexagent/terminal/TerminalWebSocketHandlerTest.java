package com.labex.labexagent.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.labexagent.worker.SandboxWorker;
import com.labex.labexagent.worker.WorkerRunSpec;
import com.labex.security.JwtUtil;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class TerminalWebSocketHandlerTest {
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final TerminalWorkspaceResolver workspaceResolver = mock(TerminalWorkspaceResolver.class);
    private final SandboxWorker sandboxWorker = mock(SandboxWorker.class);
    private final SandboxWorker.InteractiveTerminal interactiveTerminal = mock(SandboxWorker.InteractiveTerminal.class);

    private TerminalWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        when(jwtUtil.removePrefix(any())).thenAnswer(invocation -> invocation.getArgument(0));
        handler = new TerminalWebSocketHandler(jwtUtil, workspaceResolver, sandboxWorker);
    }

    @Test
    void rejectsConnectionWhenTokenOrProjectIdMissing() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/terminal"));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"error\"") && msg.getPayload().contains("Missing token or projectId")));
        verify(session).close(CloseStatus.NOT_ACCEPTABLE);
        verifyNoInteractions(jwtUtil, workspaceResolver, sandboxWorker);
    }

    @Test
    void rejectsConnectionWhenTokenIsInvalid() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/terminal?token=bad-token&projectId=10"));
        when(session.isOpen()).thenReturn(true);
        when(jwtUtil.validateToken("bad-token")).thenReturn(false);

        handler.afterConnectionEstablished(session);

        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"error\"") && msg.getPayload().contains("Invalid or expired token")));
        verify(session).close(CloseStatus.NOT_ACCEPTABLE);
        verifyNoInteractions(workspaceResolver, sandboxWorker);
    }

    @Test
    void rejectsConnectionWhenProjectAccessDenied() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/terminal?token=good-token&projectId=10"));
        when(session.isOpen()).thenReturn(true);
        when(jwtUtil.validateToken("good-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("good-token")).thenReturn(1);
        when(workspaceResolver.resolveWorkspace(1, 10))
                .thenThrow(new IllegalArgumentException("Project not found or access denied"));

        handler.afterConnectionEstablished(session);

        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"error\"") && msg.getPayload().contains("Project not found or access denied")));
        verify(session).close(CloseStatus.NOT_ACCEPTABLE);
        verifyNoInteractions(sandboxWorker);
    }

    @Test
    void authenticatesAndHandlesFullTerminalLifecycle() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/terminal?token=valid-token&projectId=100"));
        when(session.isOpen()).thenReturn(true);
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(42);

        Path rootPath = Path.of("D:/test-workspace");
        TerminalWorkspaceResolver.TerminalWorkspace workspace =
                new TerminalWorkspaceResolver.TerminalWorkspace(42, 100, rootPath);
        when(workspaceResolver.resolveWorkspace(42, 100)).thenReturn(workspace);
        when(workspaceResolver.resolveWorkingDirectory(workspace, "")).thenReturn(rootPath);
        when(sandboxWorker.openTerminal(any(WorkerRunSpec.class), any(SandboxWorker.TerminalSpec.class)))
                .thenReturn(interactiveTerminal);

        // 1. Establish connection
        handler.afterConnectionEstablished(session);
        verify(session, never()).close(any(CloseStatus.class));

        // 2. Send "create" message
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"create\",\"cwd\":\"\",\"cols\":100,\"rows\":30}"));

        ArgumentCaptor<SandboxWorker.TerminalSpec> specCaptor =
                ArgumentCaptor.forClass(SandboxWorker.TerminalSpec.class);
        verify(sandboxWorker).openTerminal(any(WorkerRunSpec.class), specCaptor.capture());
        SandboxWorker.TerminalSpec spec = specCaptor.getValue();
        assertThat(spec.cols()).isEqualTo(100);
        assertThat(spec.rows()).isEqualTo(30);
        assertThat(spec.workingDirectory()).isEqualTo(rootPath);

        // Verify "created" response sent
        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"created\"") && msg.getPayload().contains("sessionId")));

        // Test output listener forwards data to WebSocket
        spec.outputListener().accept("Hello from shell\r\n");
        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"output\"") && msg.getPayload().contains("Hello from shell")));

        // Test exit listener forwards code to WebSocket
        spec.closeListener().accept(0);
        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"exit\"") && msg.getPayload().contains("\"code\":0")));

        // 3. Send "input" message
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"input\",\"data\":\"ls -la\\n\"}"));
        verify(interactiveTerminal).writeInput("ls -la\n");

        // 4. Send "resize" message
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":140,\"rows\":45}"));
        verify(interactiveTerminal).resize(140, 45);

        // 5. Send "ping" message
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));
        verify(session).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"pong\"")));

        // 6. Send "close" message
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"close\"}"));
        verify(interactiveTerminal).terminate();

        // 7. Disconnect WebSocket
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    }

    @Test
    void rejectsTextMessageWhenSessionNotAuthenticated() throws Exception {
        WebSocketSession unauthenticatedSession = mock(WebSocketSession.class);
        when(unauthenticatedSession.isOpen()).thenReturn(true);

        handler.handleTextMessage(unauthenticatedSession, new TextMessage("{\"type\":\"create\"}"));

        verify(unauthenticatedSession).sendMessage(argThat((TextMessage msg) ->
                msg.getPayload().contains("\"type\":\"error\"") && msg.getPayload().contains("Not authenticated")));
        verify(unauthenticatedSession).close(CloseStatus.NOT_ACCEPTABLE);
    }
}
