package vn.ptit.iot.websocket;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import vn.ptit.iot.dto.WebSocketEvent;
@Component
public class UpdatesSocket extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(UpdatesSocket.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    public UpdatesSocket(ObjectMapper json) { this.json = json; }
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 2000, 65536));
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session.getId()); }
    @EventListener public void sessionDestroyed(HttpSessionDestroyedEvent event) {
        sessions.forEach((id, session) -> {
            if (event.getId().equals(session.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME))) {
                sessions.remove(id);
                try { session.close(CloseStatus.POLICY_VIOLATION); }
                catch (IOException ex) { log.debug("Session socket close failed", ex); }
            }
        });
    }
    public void broadcast(String type, Object data) {
        final TextMessage message;
        try { message = new TextMessage(json.writeValueAsString(new WebSocketEvent(type, data))); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize WebSocket data", ex); }
        sessions.forEach((id, session) -> {
            try { if (session.isOpen()) session.sendMessage(message); }
            catch (IOException | IllegalStateException ex) {
                sessions.remove(id);
                try { session.close(); } catch (IOException closeError) { log.debug("Socket close failed", closeError); }
                log.debug("WebSocket client disconnected", ex);
            }
        });
    }
}
