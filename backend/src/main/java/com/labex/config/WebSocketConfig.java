package com.labex.config;

import com.labex.labexagent.terminal.TerminalHandshakeInterceptor;
import com.labex.labexagent.terminal.TerminalWebSocketHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalHandler;
    private final TerminalHandshakeInterceptor handshakeInterceptor;
    private final String[] allowedOriginPatterns;

    public WebSocketConfig(
            TerminalWebSocketHandler terminalHandler,
            TerminalHandshakeInterceptor handshakeInterceptor,
            @Value("${labex-agent.websocket.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String allowedOrigins) {
        this.terminalHandler = terminalHandler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOriginPatterns = resolveAllowedOriginPatterns(allowedOrigins);
    }

    private static String[] resolveAllowedOriginPatterns(String allowedOrigins) {
        List<String> patterns = new ArrayList<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isBlank())
                    .forEach(patterns::add);
        }
        // 允许开发环境下常见的 Origin 模式，兼容不同端口的开发服务器与代理
        if (!patterns.contains("http://localhost:*")) patterns.add("http://localhost:*");
        if (!patterns.contains("http://127.0.0.1:*")) patterns.add("http://127.0.0.1:*");
        if (!patterns.contains("http://localhost:3000")) patterns.add("http://localhost:3000");
        if (!patterns.contains("http://127.0.0.1:3000")) patterns.add("http://127.0.0.1:3000");
        return patterns.toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalHandler, "/ws/terminal")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
