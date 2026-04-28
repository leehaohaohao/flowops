package com.nexa.flowops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import com.nexa.flowops.service.LogService;
import org.springframework.web.socket.handler.TextMessageHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LogService logService;

    public WebSocketConfig(LogService logService) {
        this.logService = logService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TextMessageHandler(), "/ws/logs")
                .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                                  org.springframework.web.socket.WebSocketHandler wsHandler,
                                                  java.util.Map<String, Object> attributes) throws Exception {
                        return true;
                    }

                    @Override
                    public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                               org.springframework.web.socket.WebSocketHandler wsHandler,
                                               org.springframework.web.socket.session.WebSocketSession session) {
                    }
                });
    }
}
