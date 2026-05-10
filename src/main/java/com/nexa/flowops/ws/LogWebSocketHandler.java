package com.nexa.flowops.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 实时日志 WebSocket 推送
 * 客户端连接后发送文件名，服务端先发送已有内容，之后每 2 秒检查文件增量并推送
 */
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocketHandler.class);

    private final String logBasePath = "/data/flowops/services/logs";
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final ConcurrentHashMap<String, ScheduledFuture<?>> watchTasks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String filename = message.getPayload().trim();
        if (filename.isEmpty()) return;

        // 先停止旧的文件监看任务
        cancelWatch(session.getId());

        Path filePath = Path.of(logBasePath, filename);
        if (!Files.exists(filePath)) {
            session.sendMessage(new TextMessage("日志文件不存在: " + filename));
            return;
        }

        // 发送已有内容
        String content = Files.readString(filePath);
        session.sendMessage(new TextMessage(content));
        log.info("[{}] 已发送日志 {} 的已有内容 ({} 字符)", session.getId(), filename, content.length());

        // 启动增量监看：每 2 秒检查文件是否追加了新内容
        long[] lastSize = {Files.size(filePath)};
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (!session.isOpen()) {
                    cancelWatch(session.getId());
                    return;
                }
                long currentSize = Files.size(filePath);
                if (currentSize > lastSize[0]) {
                    // 文件有增长，读取新增部分
                    try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                        raf.seek(lastSize[0]);
                        byte[] bytes = new byte[(int) (currentSize - lastSize[0])];
                        raf.readFully(bytes);
                        String newContent = new String(bytes);
                        session.sendMessage(new TextMessage(newContent));
                    }
                    lastSize[0] = currentSize;
                }
            } catch (Exception e) {
                log.warn("[{}] 日志监看异常: {}", session.getId(), e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);

        watchTasks.put(session.getId(), task);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 连接关闭: {}, 状态={}", session.getId(), status);
        cancelWatch(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输错误: {}", session.getId(), exception);
        cancelWatch(session.getId());
    }

    private void cancelWatch(String sessionId) {
        ScheduledFuture<?> task = watchTasks.remove(sessionId);
        if (task != null) {
            task.cancel(false);
        }
    }
}
