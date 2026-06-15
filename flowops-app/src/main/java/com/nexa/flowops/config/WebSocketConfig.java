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
    private final LogWebSocketHandler logWebSocketHandler;

    public WebSocketConfig(ContainerLogWebSocketHandler containerLogHandler,
                           LogWebSocketHandler logWebSocketHandler) {
        this.containerLogHandler = containerLogHandler;
        this.logWebSocketHandler = logWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logWebSocketHandler, "/ws/logs").setAllowedOrigins("*");
        registry.addHandler(containerLogHandler, "/ws/container-logs").setAllowedOrigins("*");
    }
}
