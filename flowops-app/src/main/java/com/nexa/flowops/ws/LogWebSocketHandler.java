package com.nexa.flowops.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.service.log.LogSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 部署日志实时 WebSocket 推送
 * 客户端连接时通过 URL query 参数传递 token 进行认证
 * 客户端发送 JSON: {"serviceId":1, "type":"deploy", "filename":"14-30-22.log"}
 * 服务端先发送已有内容，之后每 2 秒检查文件增量并推送
 */
@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    private final LogSource logSource;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolTaskScheduler taskScheduler;
    private final ConcurrentHashMap<String, ScheduledFuture<?>> watchTasks = new ConcurrentHashMap<>();

    public LogWebSocketHandler(LogSource logSource, ThreadPoolTaskScheduler taskScheduler) {
        this.logSource = logSource;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 从 URL query 参数获取 token 进行认证
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        String token = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    token = kv[1];
                    break;
                }
            }
        }
        if (token == null || token.isEmpty()) {
            session.close(new CloseStatus(4001, "缺少认证 token"));
            return;
        }
        if (StpUtil.getLoginIdByToken(token) == null) {
            session.close(new CloseStatus(4003, "认证失败"));
            return;
        }
        log.info("日志 WebSocket 连接建立: {}", session.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> params = objectMapper.readValue(message.getPayload(), Map.class);
        Number serviceIdNum = (Number) params.get("serviceId");
        String type = (String) params.get("type");
        String filename = (String) params.get("filename");
        if (serviceIdNum == null || type == null || filename == null) return;

        Long serviceId = serviceIdNum.longValue();

        // 路径遍历校验
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            session.sendMessage(new TextMessage("非法文件名"));
            return;
        }

        cancelWatch(session.getId());

        String date = (String) params.getOrDefault("date", "");
        Path filePath = logSource.resolveLogPath(serviceId, type, date, filename);
        if (filePath == null || !Files.exists(filePath)) {
            session.sendMessage(new TextMessage("日志文件不存在"));
            return;
        }

        // 发送已有内容
        String content = Files.readString(filePath);
        session.sendMessage(new TextMessage(content));
        log.info("[{}] 已发送日志 {} 的已有内容 ({} 字符)", session.getId(), filename, content.length());

        // 启动增量监看：每 2 秒检查文件是否追加了新内容
        long[] lastSize = {Files.size(filePath)};
        ScheduledFuture<?> task = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!session.isOpen()) {
                    cancelWatch(session.getId());
                    return;
                }
                long currentSize = Files.size(filePath);
                if (currentSize > lastSize[0]) {
                    try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                        raf.seek(lastSize[0]);
                        byte[] bytes = new byte[(int) (currentSize - lastSize[0])];
                        raf.readFully(bytes);
                        session.sendMessage(new TextMessage(new String(bytes)));
                    }
                    lastSize[0] = currentSize;
                }
            } catch (Exception e) {
                log.warn("[{}] 日志监看异常: {}", session.getId(), e.getMessage());
            }
        }, 2000);

        watchTasks.put(session.getId(), task);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("日志 WebSocket 连接关闭: {}, 状态={}", session.getId(), status);
        cancelWatch(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("日志 WebSocket 传输错误: {}", session.getId(), exception);
        cancelWatch(session.getId());
    }

    private void cancelWatch(String sessionId) {
        ScheduledFuture<?> task = watchTasks.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
