package com.ptit.iot.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ptit.iot.dto.common.WsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketBroadcaster extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcaster.class);

    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WebSocketBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (session.getPrincipal() == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Authentication required"));
            return;
        }
        sessions.put(session.getId(), session);
        log.debug("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.debug("WebSocket disconnected: {}", session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        log.debug("WebSocket transport error for {}: {}", session.getId(), exception.getMessage());
    }

    public void broadcast(String eventName, Object data) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(new WsEvent<>(eventName, data));
        } catch (JsonProcessingException ex) {
            log.error("Cannot serialize WebSocket event {}", eventName, ex);
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                sessions.remove(session.getId());
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException ex) {
                sessions.remove(session.getId());
                log.debug("Cannot send WebSocket event to {}: {}", session.getId(), ex.getMessage());
            }
        }
    }
}
