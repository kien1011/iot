package vn.ptit.iot.config;

import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import vn.ptit.iot.websocket.LiveUpdates;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {
    private final LiveUpdates live;
    public WebSocketConfig(LiveUpdates live) { this.live = live; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin only; the SecurityFilterChain also authenticates the handshake.
        var handshake = new DefaultHandshakeHandler();
        handshake.setSupportedProtocols("bearer");
        registry.addHandler(live, "/ws").setHandshakeHandler(handshake).addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet)) return false;
                Object token = servlet.getServletRequest().getAttribute(TokenAuthenticationFilter.TOKEN_ATTRIBUTE);
                if (token == null) return false;
                attributes.put(TokenAuthenticationFilter.TOKEN_ATTRIBUTE, token);
                return true;
            }

            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler handler, Exception exception) { }
        });
    }
}
