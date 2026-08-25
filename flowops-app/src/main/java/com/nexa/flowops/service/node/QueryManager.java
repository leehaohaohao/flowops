package com.nexa.flowops.service.node;

import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Query.ContainerLogsRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusRequest;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.codec.ProtocolCodec;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.RunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
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
 * 时旧查询未回执，旧查询会被置为「被取代」而立即失败，避免悬挂。
 *
 * <p>注：子节点（flowops-executor）的响应 Envelope 已回填请求的 request_id（协议层 codec
 * builder 支持），但 v0.4.0 回调签名不透传 request_id，故此处无法按 request_id 关联。
 * 后续协议升级在回调中透传 request_id 后，可改为按 request_id 关联，以支持同节点并发
 * 查询与断线重连时的准确关联。
 */
@Component
public class QueryManager {

    private static final Logger log = LoggerFactory.getLogger(QueryManager.class);

    /** 同步查询默认超时 */
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    /** runnerId -> 进行中的状态查询 */
    private final ConcurrentMap<String, CompletableFuture<ContainerStatusResponse>> pendingStatus = new ConcurrentHashMap<>();
    /** runnerId -> 进行中的日志查询 */
    private final ConcurrentMap<String, CompletableFuture<ContainerLogsResponse>> pendingLogs = new ConcurrentHashMap<>();

    /**
     * ObjectProvider 惰性获取 NexaMaster，避免构造循环：
     * FlowOpsMasterListener → QueryManager → NexaMaster → FlowOpsMasterListener
     */
    private final ObjectProvider<NexaMaster> nexaMasterProvider;

    public QueryManager(ObjectProvider<NexaMaster> nexaMasterProvider) {
        this.nexaMasterProvider = nexaMasterProvider;
    }

    public static long defaultTimeoutMs() {
        return DEFAULT_TIMEOUT_MS;
    }

    private NexaMaster nexaMaster() {
        return nexaMasterProvider.getObject();
    }

    // ==================== 查询下发（同步等待） ====================

    public Optional<ContainerStatusResponse> queryContainerStatus(String runnerId,
                                                                  ContainerStatusRequest req,
                                                                  long timeoutMs) {
        Envelope envelope = ProtocolCodec.buildContainerStatusRequest(runnerId, req);
        CompletableFuture<ContainerStatusResponse> future = new CompletableFuture<>();
        CompletableFuture<ContainerStatusResponse> previous = pendingStatus.put(runnerId, future);
        if (previous != null) {
            // 同节点已有未回执查询，置为被取代，避免调用方悬挂
            previous.completeExceptionally(new TimeoutException("查询被同一节点的新查询取代: runnerId=" + runnerId));
        }
        return await(runnerId, envelope, future, timeoutMs, "状态查询");
    }

    public Optional<ContainerLogsResponse> queryContainerLogs(String runnerId,
                                                              ContainerLogsRequest req,
                                                              long timeoutMs) {
        Envelope envelope = ProtocolCodec.buildContainerLogsRequest(runnerId, req);
        CompletableFuture<ContainerLogsResponse> future = new CompletableFuture<>();
        CompletableFuture<ContainerLogsResponse> previous = pendingLogs.put(runnerId, future);
        if (previous != null) {
            previous.completeExceptionally(new TimeoutException("查询被同一节点的新查询取代: runnerId=" + runnerId));
        }
        return awaitLogs(runnerId, envelope, future, timeoutMs);
    }

    private Optional<ContainerStatusResponse> await(String runnerId, Envelope envelope,
                                                    CompletableFuture<ContainerStatusResponse> future,
                                                    long timeoutMs, String label) {
        String requestId = envelope.getRequestId();
        boolean sent = nexaMaster().sendTo(runnerId, envelope);
        if (!sent) {
            pendingStatus.remove(runnerId, future);
            log.warn("[Master] {}下发失败，节点不可写: runnerId={}", label, runnerId);
            return Optional.empty();
        }
        log.debug("[Master] {}已下发: runnerId={}, requestId={}", label, runnerId, requestId);
        try {
            ContainerStatusResponse resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Optional.of(resp);
        } catch (TimeoutException e) {
            pendingStatus.remove(runnerId, future);
            log.warn("[Master] {}超时（{}ms）: runnerId={}, requestId={}", label, timeoutMs, runnerId, requestId);
            return Optional.empty();
        } catch (Exception e) {
            pendingStatus.remove(runnerId, future);
            log.warn("[Master] {}异常: runnerId={}, requestId={}, err={}", label, runnerId, requestId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ContainerLogsResponse> awaitLogs(String runnerId, Envelope envelope,
                                                      CompletableFuture<ContainerLogsResponse> future,
                                                      long timeoutMs) {
        String requestId = envelope.getRequestId();
        boolean sent = nexaMaster().sendTo(runnerId, envelope);
        if (!sent) {
            pendingLogs.remove(runnerId, future);
            log.warn("[Master] 日志查询下发失败，节点不可写: runnerId={}", runnerId);
            return Optional.empty();
        }
        log.debug("[Master] 日志查询已下发: runnerId={}, requestId={}", runnerId, requestId);
        try {
            ContainerLogsResponse resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return Optional.of(resp);
        } catch (TimeoutException e) {
            pendingLogs.remove(runnerId, future);
            log.warn("[Master] 日志查询超时（{}ms）: runnerId={}, requestId={}", timeoutMs, runnerId, requestId);
            return Optional.empty();
        } catch (Exception e) {
            pendingLogs.remove(runnerId, future);
            log.warn("[Master] 日志查询异常: runnerId={}, requestId={}, err={}", runnerId, requestId, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 回执入口（由 FlowOpsMasterListener 调用） ====================

    public void onContainerStatus(RunnerSession session, ContainerStatusResponse resp) {
        CompletableFuture<ContainerStatusResponse> future = pendingStatus.remove(session.getRunnerId());
        if (future == null) {
            log.warn("[Master] 收到未知状态查询回执: runnerId={}, running={}", session.getRunnerId(), resp.getRunning());
            return;
        }
        future.complete(resp);
    }

    public void onContainerLogs(RunnerSession session, ContainerLogsResponse resp) {
        CompletableFuture<ContainerLogsResponse> future = pendingLogs.remove(session.getRunnerId());
        if (future == null) {
            log.warn("[Master] 收到未知日志查询回执: runnerId={}", session.getRunnerId());
            return;
        }
        future.complete(resp);
    }

    /**
     * 节点掉线时，将该节点上所有进行中的查询置为失败
     */
    public void failPendingForNode(String runnerId, String reason) {
        if (runnerId == null) return;
        int failed = 0;
        CompletableFuture<ContainerStatusResponse> statusFuture = pendingStatus.remove(runnerId);
        if (statusFuture != null) {
            statusFuture.completeExceptionally(new IllegalStateException("节点掉线: " + reason));
            failed++;
        }
        CompletableFuture<ContainerLogsResponse> logsFuture = pendingLogs.remove(runnerId);
        if (logsFuture != null) {
            logsFuture.completeExceptionally(new IllegalStateException("节点掉线: " + reason));
            failed++;
        }
        if (failed > 0) {
            log.warn("[Master] 节点 {} 掉线，已失败 {} 个进行中的查询", runnerId, failed);
        }
    }
}
