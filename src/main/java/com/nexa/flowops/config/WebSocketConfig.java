package com.nexa.flowops.config;

import com.nexa.flowops.ws.ContainerLogWebSocketHandler;
import com.nexa.flowops.ws.LogWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ContainerLogWebSocketHandler containerLogHandler;

    @Autowired
    @Lazy
    private ThreadPoolTaskScheduler taskScheduler;

    public WebSocketConfig(ContainerLogWebSocketHandler containerLogHandler) {
        this.containerLogHandler = containerLogHandler;
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ws-log-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new LogWebSocketHandler(taskScheduler), "/ws/logs").setAllowedOrigins("*");
        registry.addHandler(containerLogHandler, "/ws/container-logs").setAllowedOrigins("*");
    }
}
