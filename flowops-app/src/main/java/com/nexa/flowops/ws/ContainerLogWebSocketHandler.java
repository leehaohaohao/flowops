package com.nexa.flowops.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.util.DockerUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 容器运行日志实时推送 WebSocket
 * 客户端发送 JSON: {"serviceId": 1, "tail": 200, "follow": true, "since": "2h", "until": "", "timestamps": true, "grep": "ERROR"}
 * 服务端推送: {"type":"status/statusLine/line/end/error", ...}
 */
@Component
public class ContainerLogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ContainerLogWebSocketHandler.class);

    private final DeployServiceMapper serviceMapper;
    private final DockerUtil dockerUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService readerPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> sessionFlags = new ConcurrentHashMap<>();

    public ContainerLogWebSocketHandler(DeployServiceMapper serviceMapper, DockerUtil dockerUtil) {
        this.serviceMapper = serviceMapper;
        this.dockerUtil = dockerUtil;
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
        log.info("容器日志 WebSocket 连接建立: {}", session.getId());
        sessionFlags.put(session.getId(), new AtomicBoolean(true));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        try {
            Map<String, Object> params = objectMapper.readValue(message.getPayload(), Map.class);
            Number serviceIdNum = (Number) params.get("serviceId");
            if (serviceIdNum == null) {
                sendJson(session, Map.of("type", "error", "msg", "缺少 serviceId"));
                return;
            }

            Long serviceId = serviceIdNum.longValue();
            int tail = params.containsKey("tail") ? ((Number) params.get("tail")).intValue() : 200;
            boolean follow = !params.containsKey("follow") || (boolean) params.get("follow");
            String since = params.containsKey("since") ? (String) params.get("since") : null;
            String until = params.containsKey("until") ? (String) params.get("until") : null;
            boolean timestamps = params.containsKey("timestamps") && (boolean) params.get("timestamps");
            String grep = params.containsKey("grep") ? (String) params.get("grep") : null;

            DeployService service = serviceMapper.selectById(serviceId);
            if (service == null) {
                sendJson(session, Map.of("type", "error", "msg", "服务不存在"));
                return;
            }

            // 停止该会话上一次的日志流
            stopProcess(sessionId);

            sendJson(session, Map.of("type", "status", "msg", "正在连接容器日志..."));

            String volumeDir = service.getVolumeDir();
            String serviceName = service.getName();

            // 构建 docker compose logs 命令
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    "docker", "compose", "logs",
                    "--tail", String.valueOf(tail),
                    "--no-color",
                    follow ? "--follow" : "--no-follow"
            ));
            if (timestamps) cmd.add("--timestamps");
            if (since != null && !since.isEmpty()) { cmd.add("--since"); cmd.add(since); }
            if (until != null && !until.isEmpty()) { cmd.add("--until"); cmd.add(until); }
            cmd.add(serviceName);

            ProcessBuilder pb = dockerUtil.newProcessBuilder(cmd.toArray(new String[0]));
            pb.directory(new java.io.File(volumeDir));

            log.info("[{}] 启动容器日志流: follow={}, tail={}, since={}, until={}, timestamps={}, grep={}",
                    serviceName, follow, tail, since, until, timestamps, grep);
            Process process = pb.start();
            activeProcesses.put(sessionId, process);

            AtomicBoolean running = sessionFlags.get(sessionId);
            if (running == null) running = new AtomicBoolean(true);

            final AtomicBoolean flag = running;

            // 线程池读取并推送日志行
            final String grepFilter = grep;
            readerPool.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String desc = (follow ? "实时跟踪" : "最近 " + tail + " 行");
                    if (since != null && !since.isEmpty()) desc += ", since=" + since;
                    if (grepFilter != null && !grepFilter.isEmpty()) desc += ", grep=" + grepFilter;
                    sendJson(session, Map.of("type", "statusLine", "msg",
                            "已连接 " + serviceName + " 容器日志 (" + desc + ")"));

                    String line;
                    while (flag.get() && session.isOpen() && (line = reader.readLine()) != null) {
                        if (grepFilter != null && !grepFilter.isEmpty() && !line.contains(grepFilter)) {
                            continue;
                        }
                        sendJson(session, Map.of("type", "line", "msg", line));
                    }
                } catch (Exception e) {
                    if (flag.get()) {
                        log.warn("[{}] 日志读取异常: {}", sessionId, e.getMessage());
                    }
                } finally {
                    process.destroy();
                    activeProcesses.remove(sessionId);
                    if (flag.get() && session.isOpen()) {
                        try {
                            sendJson(session, Map.of("type", "end", "msg", "日志流已结束"));
                        } catch (Exception ignored) {
                        }
                    }
                    log.info("[{}] 容器日志流结束: {}", serviceName, sessionId);
                }
            }, "container-log-" + sessionId);

        } catch (Exception e) {
            log.error("[{}] 处理容器日志请求失败", sessionId, e);
            try {
                sendJson(session, Map.of("type", "error", "msg", "启动日志流失败: " + e.getMessage()));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        log.info("容器日志 WebSocket 关闭: {}, 状态={}", sessionId, status);
        stopProcess(sessionId);
        sessionFlags.remove(sessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("容器日志 WebSocket 传输错误: {}", session.getId(), exception);
        stopProcess(session.getId());
    }

    private void stopProcess(String sessionId) {
        AtomicBoolean flag = sessionFlags.get(sessionId);
        if (flag != null) flag.set(false);

        Process process = activeProcesses.remove(sessionId);
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) throws Exception {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(data)));
        }
    }
}
