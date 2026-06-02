package com.toonflow.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.ai.agent.ProductionAgentService;
import com.toonflow.ai.agent.ScriptAgentService;
import com.toonflow.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class SocketIoWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Lazy
    @Autowired
    private ScriptAgentService scriptAgentService;

    @Lazy
    @Autowired
    private ProductionAgentService productionAgentService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sidToSession = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        session.getAttributes().put("sid", sid);
        session.getAttributes().put("namespaceContexts", new ConcurrentHashMap<String, Map<String, Object>>());
        sidToSession.put(sid, session);

        // Send EIO open packet
        String openPacket = "0{\"sid\":\"" + sid + "\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":1000000}";
        sendMessage(session, openPacket);

        // Start ping scheduler
        ScheduledFuture<?> pingFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (session.isOpen()) {
                    sendMessage(session, "2");
                }
            } catch (Exception e) {
                log.warn("Ping failed for sid={}", sid, e);
            }
        }, 25, 25, TimeUnit.SECONDS);
        session.getAttributes().put("pingFuture", pingFuture);

        log.info("Socket.IO connection established: sid={}", sid);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        if (payload == null || payload.isEmpty()) return;

        char eioType = payload.charAt(0);
        String data = payload.length() > 1 ? payload.substring(1) : "";
        handleEioMessage(session, eioType, data);
    }

    private void handleEioMessage(WebSocketSession session, char type, String data) throws Exception {
        switch (type) {
            case '2': // ping
                if ("probe".equals(data)) {
                    sendMessage(session, "3probe");
                } else {
                    sendMessage(session, "3");
                }
                break;
            case '3': // pong - reset ping timer (nothing to do, scheduler runs independently)
                break;
            case '4': // Socket.IO message
                handleSocketIoPacket(session, data);
                break;
            case '5': // upgrade - ignore
                break;
            default:
                log.debug("Unknown EIO packet type: {}", type);
        }
    }

    private void handleSocketIoPacket(WebSocketSession session, String sioData) throws Exception {
        if (sioData == null || sioData.isEmpty()) return;

        char sioType = sioData.charAt(0);
        String rest = sioData.length() > 1 ? sioData.substring(1) : "";

        // Parse optional namespace: starts with '/' and ends at ','
        String namespace = "/";
        String body = rest;

        if (rest.startsWith("/")) {
            int commaIdx = rest.indexOf(',');
            if (commaIdx >= 0) {
                namespace = rest.substring(0, commaIdx);
                body = rest.substring(commaIdx + 1);
            } else {
                namespace = rest;
                body = "";
            }
        }

        switch (sioType) {
            case '0': // connect
                handleNamespaceConnect(session, namespace, body);
                break;
            case '1': // disconnect
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> ctxMap =
                        (Map<String, Map<String, Object>>) session.getAttributes().get("namespaceContexts");
                if (ctxMap != null) ctxMap.remove(namespace);
                break;
            case '2': // event
                handleNamespaceEvent(session, namespace, body);
                break;
            default:
                log.debug("Unknown SIO packet type: {}", sioType);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleNamespaceConnect(WebSocketSession session, String namespace, String authJson) throws Exception {
        String sid = (String) session.getAttributes().get("sid");

        // Validate JWT for protected namespaces
        if (namespace.startsWith("/api/socket/")) {
            Map<String, Object> auth = Collections.emptyMap();
            if (authJson != null && !authJson.isEmpty()) {
                try {
                    auth = objectMapper.readValue(authJson, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse auth JSON: {}", authJson);
                }
            }

            String token = (String) auth.get("token");
            if (token == null || !validateJwt(token)) {
                log.warn("JWT validation failed for namespace={}", namespace);
                // Send disconnect
                sendMessage(session, "41" + namespace);
                return;
            }

            // Store namespace context
            Map<String, Object> ctx = new HashMap<>(auth);
            Map<String, Map<String, Object>> ctxMap =
                    (Map<String, Map<String, Object>>) session.getAttributes().get("namespaceContexts");
            if (ctxMap != null) ctxMap.put(namespace, ctx);
        }

        // Send connect ack
        String ack = "40" + (namespace.equals("/") ? "" : namespace + ",") + "{\"sid\":\"" + sid + "\"}";
        sendMessage(session, ack);
        log.info("Namespace connected: sid={}, namespace={}", sid, namespace);
    }

    @SuppressWarnings("unchecked")
    private void handleNamespaceEvent(WebSocketSession session, String namespace, String eventJsonArray) {
        try {
            List<Object> arr = objectMapper.readValue(eventJsonArray, new TypeReference<List<Object>>() {});
            if (arr == null || arr.isEmpty()) return;

            String eventName = (String) arr.get(0);
            Object eventData = arr.size() > 1 ? arr.get(1) : null;

            String sid = (String) session.getAttributes().get("sid");
            Map<String, Map<String, Object>> ctxMap =
                    (Map<String, Map<String, Object>>) session.getAttributes().get("namespaceContexts");
            Map<String, Object> ctx = ctxMap != null ? ctxMap.get(namespace) : Collections.emptyMap();

            if (namespace.contains("scriptAgent")) {
                handleScriptAgentEvent(session, namespace, sid, ctx, eventName, eventData);
            } else if (namespace.contains("productionAgent")) {
                handleProductionAgentEvent(session, namespace, sid, ctx, eventName, eventData);
            }
        } catch (Exception e) {
            log.error("Error handling namespace event: namespace={}", namespace, e);
        }
    }

    @Async
    protected void handleScriptAgentEvent(WebSocketSession session, String namespace, String sid,
                                           Map<String, Object> ctx, String eventName, Object eventData) {
        if (!"chat".equals(eventName)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = eventData instanceof Map ? (Map<String, Object>) eventData : Collections.emptyMap();
            String content = (String) data.getOrDefault("content", "");
            String isolationKey = (String) ctx.getOrDefault("isolationKey", sid);
            String projectId = ctx.get("projectId") != null ? ctx.get("projectId").toString() : null;

            scriptAgentService.runDecision(session, namespace, sid, isolationKey, projectId, content);
        } catch (Exception e) {
            log.error("ScriptAgent event error", e);
            emitSafe(session, namespace, "error", Map.of("message", e.getMessage()));
        }
    }

    @Async
    protected void handleProductionAgentEvent(WebSocketSession session, String namespace, String sid,
                                               Map<String, Object> ctx, String eventName, Object eventData) {
        if (!"chat".equals(eventName)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = eventData instanceof Map ? (Map<String, Object>) eventData : Collections.emptyMap();
            String content = (String) data.getOrDefault("content", "");
            String isolationKey = (String) ctx.getOrDefault("isolationKey", sid);
            String projectId = ctx.get("projectId") != null ? ctx.get("projectId").toString() : null;

            productionAgentService.runDecision(session, namespace, sid, isolationKey, projectId, (String) null, content);
        } catch (Exception e) {
            log.error("ProductionAgent event error", e);
            emitSafe(session, namespace, "error", Map.of("message", e.getMessage()));
        }
    }

    public void emit(WebSocketSession session, String namespace, String eventName, Object data) {
        try {
            String nsPrefix = namespace.equals("/") ? "" : namespace + ",";
            List<Object> arr = new ArrayList<>();
            arr.add(eventName);
            arr.add(data);
            String json = objectMapper.writeValueAsString(arr);
            sendMessage(session, "42" + nsPrefix + json);
        } catch (Exception e) {
            log.error("Failed to emit event={} to namespace={}", eventName, namespace, e);
        }
    }

    private void emitSafe(WebSocketSession session, String namespace, String eventName, Object data) {
        emit(session, namespace, eventName, data);
    }

    public void emitToSid(String sid, String namespace, String eventName, Object data) {
        WebSocketSession session = sidToSession.get(sid);
        if (session != null && session.isOpen()) {
            emit(session, namespace, eventName, data);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sid = (String) session.getAttributes().get("sid");
        if (sid != null) {
            sidToSession.remove(sid);
        }
        ScheduledFuture<?> pingFuture = (ScheduledFuture<?>) session.getAttributes().get("pingFuture");
        if (pingFuture != null) {
            pingFuture.cancel(false);
        }
        log.info("Socket.IO connection closed: sid={}, status={}", sid, status);
    }

    private synchronized void sendMessage(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException e) {
            log.warn("Failed to send message to session", e);
        }
    }

    private boolean validateJwt(String token) {
        try {
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            String tokenKey = jdbcTemplate.queryForObject(
                    "SELECT value FROM o_setting WHERE key = 'tokenKey'", String.class);
            if (tokenKey == null || tokenKey.isEmpty()) return true; // no key configured, allow
            JwtUtil.parseToken(token, tokenKey);
            return true;
        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }
}
