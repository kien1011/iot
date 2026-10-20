package vn.ptit.iot.config;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.web.socket.config.annotation.*;
import vn.ptit.iot.websocket.UpdatesSocket;
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final UpdatesSocket updates;
    public WebSocketConfig(UpdatesSocket updates) { this.updates = updates; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Default same-origin checks; the HTTP handshake also requires a session.
        registry.addHandler(updates, "/ws")
            .addInterceptors(new HttpSessionHandshakeInterceptor(List.of())); // Copy only the HTTP session ID.
    }
}
