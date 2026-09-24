package com.nexa.flowops.service.node;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Query.ContainerLogsRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusRequest;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.RunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 远程查询管理：向子节点下发容器状态 / 容器日志查询请求并同步等待回执。
 *
 * <p>关联方式：协议 v0.4.0 的回执回调 {@code onContainerStatus/onContainerLogs} 只携带
 * RunnerSession 与响应体（不带 Envelope.request_id），因此这里按 runnerId 关联，同一节点
 * 同一时刻只允许一个进行中的查询（HTTP 调用方同步等待，天然串行）。若向同一节点发起新查询
 * 时旧查询未回执，旧查询会被置为“被取代”而立即失败，避免悬挂。
 *
 * <p>查询还记录发起时的<b>生效会话代次</b>：节点掉线清理只失败化同一代次的查询，
 * 避免在“读注册表 → 清理”窗口内注册的新会话的查询被误失败化（见 D.2）。
 */
@Component
public class QueryManager {

    private static final Logger log = LoggerFactory.getLogger(QueryManager.class);

    /** 同步查询默认超时 */
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    /** runnerId -> 进行中的状态查询 */
    private final ConcurrentMap<String, PendingQuery<ContainerStatusResponse>> pendingStatus = new ConcurrentHashMap<>();
    /** runnerId -> 进行中的日志查询 */
    private final ConcurrentMap<String, PendingQuery<ContainerLogsResponse>> pendingLogs = new ConcurrentHashMap<>();

    private final NodeService nodeService;

    public QueryManager(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    public static long defaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    // ==================== 查询下发（同步等待） ====================

    public Optional<ContainerStatusResponse> queryContainerStatus(String runnerId,
                                                                  ContainerStatusRequest req,
                                                                  long timeoutMs) {
        Envelope envelope = ProtocolCodec.buildContainerStatusRequest(runnerId, req);
        PendingQuery<ContainerStatusResponse> pending = nodeService.withRunnerLock(runnerId, () -> {
            NodeService.SessionTarget target = nodeService.getCurrentTarget(runnerId).orElse(null);
            if (target == null) return null;
            PendingQuery<ContainerStatusResponse> next = new PendingQuery<>(target.generation(), new CompletableFuture<>());
            PendingQuery<ContainerStatusResponse> previous = pendingStatus.put(runnerId, next);
            if (previous != null) {
                previous.future().completeExceptionally(new TimeoutException("查询被同一节点的新查询取代: runnerId=" + runnerId));
            }
            if (!target.send(envelope)) {
                pendingStatus.remove(runnerId, next);
                return null;
            }
            return next;
        });
        if (pending == null) {
            log.warn("[Master] 状态查询下发失败，节点不可写: runnerId={}", runnerId);
            return Optional.empty();
        }
        return await(runnerId, envelope, pending, timeoutMs, "状态查询");
    }

    public Optional<ContainerLogsResponse> queryContainerLogs(String runnerId,
                                                              ContainerLogsRequest req,
                                                              long timeoutMs) {
        Envelope envelope = ProtocolCodec.buildContainerLogsRequest(runnerId, req);
        PendingQuery<ContainerLogsResponse> pending = nodeService.withRunnerLock(runnerId, () -> {
            NodeService.SessionTarget target = nodeService.getCurrentTarget(runnerId).orElse(null);
            if (target == null) return null;
            PendingQuery<ContainerLogsResponse> next = new PendingQuery<>(target.generation(), new CompletableFuture<>());
            PendingQuery<ContainerLogsResponse> previous = pendingLogs.put(runnerId, next);
            if (previous != null) {
                previous.future().completeExceptionally(new TimeoutException("查询被同一节点的新查询取代: runnerId=" + runnerId));
            }
            if (!target.send(envelope)) {
                pendingLogs.remove(runnerId, next);
                return null;
            }
            return next;
        });
        if (pending == null) {
            log.warn("[Master] 日志查询下发失败，节点不可写: runnerId={}", runnerId);
            return Optional.empty();
        }
        return awaitLogs(runnerId, envelope, pending, timeoutMs);
    }

    private Optional<ContainerStatusResponse> await(String runnerId, Envelope envelope,
                                                    PendingQuery<ContainerStatusResponse> pending,
                                                    long timeoutMs, String label) {
        String requestId = envelope.getRequestId();
        CompletableFuture<ContainerStatusResponse> future = pending.future();
        log.debug("[Master] {}已下发: runnerId={}, requestId={}", label, runnerId, requestId);
        try {
            ContainerStatusResponse resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Optional.of(resp);
        } catch (TimeoutException e) {
            pendingStatus.remove(runnerId, pending);
            log.warn("[Master] {}超时（{}ms）: runnerId={}, requestId={}", label, timeoutMs, runnerId, requestId);
            return Optional.empty();
        } catch (Exception e) {
            pendingStatus.remove(runnerId, pending);
            log.warn("[Master] {}异常: runnerId={}, requestId={}, err={}", label, runnerId, requestId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ContainerLogsResponse> awaitLogs(String runnerId, Envelope envelope,
                                                      PendingQuery<ContainerLogsResponse> pending,
                                                      long timeoutMs) {
        String requestId = envelope.getRequestId();
        CompletableFuture<ContainerLogsResponse> future = pending.future();
        log.debug("[Master] 日志查询已下发: runnerId={}, requestId={}", runnerId, requestId);
        try {
            ContainerLogsResponse resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Optional.of(resp);
        } catch (TimeoutException e) {
            pendingLogs.remove(runnerId, pending);
            log.warn("[Master] 日志查询超时（{}ms）: runnerId={}, requestId={}", timeoutMs, runnerId, requestId);
            return Optional.empty();
        } catch (Exception e) {
            pendingLogs.remove(runnerId, pending);
            log.warn("[Master] 日志查询异常: runnerId={}, requestId={}, err={}", runnerId, requestId, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 回执入口（由 FlowOpsMasterListener 调用） ====================

    public void onContainerStatus(RunnerSession session, ContainerStatusResponse resp) {
        PendingQuery<ContainerStatusResponse> pending = pendingStatus.remove(session.getRunnerId());
        if (pending == null) {
            log.warn("[Master] 收到未知状态查询回执: runnerId={}, running={}", session.getRunnerId(), resp.getRunning());
            return;
        }
        pending.future().complete(resp);
    }

    public void onContainerLogs(RunnerSession session, ContainerLogsResponse resp) {
        PendingQuery<ContainerLogsResponse> pending = pendingLogs.remove(session.getRunnerId());
        if (pending == null) {
            log.warn("[Master] 收到未知日志查询回执: runnerId={}", session.getRunnerId());
            return;
        }
        pending.future().complete(resp);
    }

    /**
     * 节点掉线时失败化进行中的查询。
     *
     * <p><b>按会话代次归因</b>：只失败化发起时所针对代次 == 事件所属代次 的查询，
     * 避免在“读注册表 → 清理”窗口内注册的新会话的查询被误失败化。
     *
     * @param sessionGeneration 事件所属会话代次；&lt;= 0（身份未知）时不失败化任何查询，
     *                          交由各查询自身的超时兜底
     */
    public void failPendingForNode(String runnerId, long sessionGeneration, String reason) {
        if (runnerId == null) {
            return;
        }
        if (sessionGeneration <= 0) {
            log.warn("[Master] 断开事件缺少会话代次，跳过查询失败化（交由查询超时兜底）: runnerId={}, reason={}",
                    runnerId, reason);
            return;
        }
        int failed = 0;
        PendingQuery<ContainerStatusResponse> status = pendingStatus.get(runnerId);
        if (status != null && status.sessionGeneration() == sessionGeneration
                && pendingStatus.remove(runnerId, status)) {
            status.future().completeExceptionally(new IllegalStateException("节点掉线: " + reason));
            failed++;
        }
        PendingQuery<ContainerLogsResponse> logs = pendingLogs.get(runnerId);
        if (logs != null && logs.sessionGeneration() == sessionGeneration
                && pendingLogs.remove(runnerId, logs)) {
            logs.future().completeExceptionally(new IllegalStateException("节点掉线: " + reason));
            failed++;
        }
        if (failed > 0) {
            log.warn("[Master] 节点 {} 掉线，已失败 {} 个进行中的查询 (generation={})", runnerId, failed, sessionGeneration);
        }
    }

    /** 进行中的查询：所针对的会话代次 + 等待中的 future */
    private record PendingQuery<T>(long sessionGeneration, CompletableFuture<T> future) {
    }
}
