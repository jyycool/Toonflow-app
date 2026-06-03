package com.toonflow.websocket;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * Handles both HTTP polling (Engine.IO) and WebSocket upgrade at /socket.io/
 * Registered with order = -1 so it takes priority over @RequestMapping controllers.
 */
public class SocketIoRequestHandler extends WebSocketHttpRequestHandler {

    public SocketIoRequestHandler(SocketIoWebSocketHandler wsHandler) {
        super(wsHandler, new DefaultHandshakeHandler());
    }

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String upgrade = request.getHeader("Upgrade");
        if ("websocket".equalsIgnoreCase(upgrade)) {
            // Delegate to Spring's WebSocket upgrade handling
            super.handleRequest(request, response);
        } else {
            handlePolling(request, response);
        }
    }

    private void handlePolling(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers", "*");
        response.setHeader("Cache-Control", "no-cache, no-store");

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(200);
            return;
        }

        if ("POST".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(200);
            response.getWriter().write("ok");
            return;
        }

        String sid = request.getParameter("sid");
        if (sid == null) {
            // Initial EIO handshake: client has no sid yet
            String newSid = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            String json = "{\"sid\":\"" + newSid
                    + "\",\"upgrades\":[\"websocket\"]"
                    + ",\"pingInterval\":25000"
                    + ",\"pingTimeout\":20000"
                    + ",\"maxPayload\":1000000}";
            String packet = "0" + json;
            String body = packet.length() + ":" + packet;
            response.getWriter().write(body);
        } else {
            // Keep-alive / wait-for-messages polling: send noop
            response.getWriter().write("1:6");
        }
    }
}
