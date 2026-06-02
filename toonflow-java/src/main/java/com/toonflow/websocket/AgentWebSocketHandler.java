package com.toonflow.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * Agent WebSocket event emitter - delegates to SocketIoWebSocketHandler
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketHandler {

    private final SocketIoWebSocketHandler socketIoHandler;

    public void emit(WebSocketSession session, String namespace, String eventName, Object eventData) {
        socketIoHandler.emit(session, namespace, eventName, eventData);
    }

    public void emitToSid(String sid, String namespace, String eventName, Object eventData) {
        socketIoHandler.emitToSid(sid, namespace, eventName, eventData);
    }
}
