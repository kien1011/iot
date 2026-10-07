package com.ptit.iot.config;

import com.ptit.iot.websocket.WebSocketBroadcaster;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final WebSocketBroadcaster broadcaster;

    public WebSocketConfig(WebSocketBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // The frontend is served by this Spring Boot application, so the default
        // same-origin WebSocket policy is exactly what the integrated project needs.
        registry.addHandler(broadcaster, "/ws");
    }
}
