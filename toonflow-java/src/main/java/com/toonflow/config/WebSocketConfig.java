package com.toonflow.config;

import com.toonflow.websocket.SocketIoRequestHandler;
import com.toonflow.websocket.SocketIoWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Bean
    public SocketIoWebSocketHandler socketIoWebSocketHandler() {
        return new SocketIoWebSocketHandler();
    }

    /**
     * Register Socket.IO handler at /socket.io/ with order = -1 so it takes priority
     * over @RequestMapping controllers (order = 0), ensuring WebSocket upgrade requests
     * are not intercepted by REST controllers.
     */
    @Bean
    public HandlerMapping socketIoHandlerMapping() {
        SocketIoRequestHandler requestHandler = new SocketIoRequestHandler(socketIoWebSocketHandler());
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/socket.io/", requestHandler));
        mapping.setOrder(-1);
        return mapping;
    }
}
