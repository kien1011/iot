package vn.ptit.iot.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import vn.ptit.iot.config.TokenAuthenticationFilter;
import vn.ptit.iot.service.TokenService;

@Component
public class LiveUpdates extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveUpdates.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final TokenService tokens;
    public LiveUpdates(ObjectMapper json, TokenService tokens) { this.json = json; this.tokens = tokens; }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 2000, 65536));
        if (tokens.find(tokenOf(session)) == null) close(session);
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }
    @Override public void handleTransportError(WebSocketSession session, Throwable error) {
        close(session);
    }
    public void broadcast(String type, Object data) {
        final TextMessage message;
        try { message = new TextMessage(json.writeValueAsString(Map.of("type", type, "data", data))); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
        for (var session : sessions.values()) {
            if (tokens.find(tokenOf(session)) == null) { close(session); continue; }
            try { if (session.isOpen()) session.sendMessage(message); }
            catch (IOException | RuntimeException ex) {
                log.debug("WebSocket send failed", ex);
                close(session);
            }
        }
    }
    public void closeForToken(String token) {
        for (var session : sessions.values()) {
            if (token.equals(tokenOf(session))) close(session);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void closeExpiredConnections() {
        tokens.removeExpired();
        for (var session : sessions.values()) {
            if (tokens.find(tokenOf(session)) == null) close(session);
        }
    }

    private String tokenOf(WebSocketSession session) {
        return (String) session.getAttributes().get(TokenAuthenticationFilter.TOKEN_ATTRIBUTE);
    }
    private void close(WebSocketSession session) {
        sessions.remove(session.getId());
        try { session.close(); } catch (IOException ex) { log.debug("WebSocket close failed", ex); }
    }
}
