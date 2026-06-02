package com.toonflow.config;

import com.toonflow.websocket.SocketIoWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socketIoWebSocketHandler(), "/socket.io/")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public SocketIoWebSocketHandler socketIoWebSocketHandler() {
        return new SocketIoWebSocketHandler();
    }
}
