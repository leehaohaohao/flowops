package com.nexa.flowops.config;

import com.nexa.flowops.ws.ContainerLogWebSocketHandler;
import com.nexa.flowops.ws.LogWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ContainerLogWebSocketHandler containerLogHandler;

    public WebSocketConfig(ContainerLogWebSocketHandler containerLogHandler) {
        this.containerLogHandler = containerLogHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new LogWebSocketHandler(), "/ws/logs").setAllowedOrigins("*");
        registry.addHandler(containerLogHandler, "/ws/container-logs").setAllowedOrigins("*");
    }
}
