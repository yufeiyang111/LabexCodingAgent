package com.labex.labexagent.terminal;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.labexagent.worker.SandboxWorker;
import com.labex.security.JwtUtil;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class TerminalWebSocketHandlerTest {
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final TerminalWorkspaceResolver workspaceResolver = mock(TerminalWorkspaceResolver.class);
    private final SandboxWorker sandboxWorker = mock(SandboxWorker.class);
    private final TerminalWebSocketHandler handler = new TerminalWebSocketHandler(jwtUtil, workspaceResolver, sandboxWorker);

    @Test
    void rejectsConnectionByPolicyBeforeAuthenticationOrWorkspaceResolution() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/api/ws/terminal?token=valid-token&projectId=12"));
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(session).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"type\":\"error\"")
                        && message.getPayload().contains("\"code\":\"TERMINAL_WEBSOCKET_DISABLED\"")
                        && message.getPayload().contains("managed terminal")));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(jwtUtil, workspaceResolver, sandboxWorker);
    }

    @Test
    void rejectsCreateAndRawInputWithoutOpeningOrWritingToWorkerTerminal() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"create\",\"cwd\":\"\"}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"input\",\"data\":\"unsafe raw input\"}"));

        verify(session, times(2)).sendMessage(argThat((TextMessage message) ->
                message.getPayload().contains("\"code\":\"TERMINAL_WEBSOCKET_DISABLED\"")));
        verify(session, times(2)).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(jwtUtil, workspaceResolver, sandboxWorker);
    }
}
