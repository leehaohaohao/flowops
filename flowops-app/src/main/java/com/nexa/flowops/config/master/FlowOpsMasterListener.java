package com.nexa.flowops.config.master;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.service.artifact.ArtifactTransferManager;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.flowops.service.node.RemoteTaskManager;
import com.nexa.protocol.Artifact.ArtifactRequest;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.NexaMasterListener;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Query.ContainerLogsResponse;
import com.nexa.protocol.Query.ContainerStatusResponse;
import com.nexa.protocol.Task.TaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 主节点侧协议事件处理：注册认证（L1）、心跳、断开、任务/查询回执、产物请求，以及会话身份校验（L2）。
 *
 * <p>重连语义（见 docs/2026-09-23-runner-connection-recovery-plan.md 步骤 4）：
 * <ul>
 *   <li>拒绝注册必须摘除并关闭会话，避免被拒连接留下可用会话，也避免其 channelInactive
 *       被当成“当前会话断开”去失败化同 ID 合法节点的任务</li>
 *   <li>旧会话的迟到断开事件不能失败化新会话的任务、也不能把已恢复的节点标成离线</li>
 *   <li>所有按节点记账的状态一律使用会话身份，不信任报文中的 runnerId</li>
 * </ul>
 */
@Component
public class FlowOpsMasterListener implements NexaMasterListener {

    private static final Logger log = LoggerFactory.getLogger(FlowOpsMasterListener.class);

    private final NodeService nodeService;
    private final RemoteTaskManager remoteTaskManager;
    private final QueryManager queryManager;
    private final NexaNodeMapper nodeMapper;
    private final ArtifactTransferManager artifactTransferManager;
    /**
     * 惰性获取 NexaMaster，避免构造循环：
     * NexaMaster → NexaMasterListener(本类) → NexaMaster
     */
    private final ObjectProvider<NexaMaster> nexaMasterProvider;
    /**
     * 心跳超时（与 nexa.master.heartbeat-timeout 同源）。
     * 直接解析配置值而不注入 NexaMasterProperties：后者随协议自动配置注册，
     * nexa.master.enabled=false 时该 bean 不存在，会导致本类装配失败。
     */
    private final Duration heartbeatTimeout;

    public FlowOpsMasterListener(NodeService nodeService,
                                 RemoteTaskManager remoteTaskManager,
                                 QueryManager queryManager,
                                 NexaNodeMapper nodeMapper,
                                 ArtifactTransferManager artifactTransferManager,
                                 ObjectProvider<NexaMaster> nexaMasterProvider,
                                 @Value("${nexa.master.heartbeat-timeout:30s}") String heartbeatTimeout) {
        this.nodeService = nodeService;
        this.remoteTaskManager = remoteTaskManager;
        this.queryManager = queryManager;
        this.nodeMapper = nodeMapper;
        this.artifactTransferManager = artifactTransferManager;
        this.nexaMasterProvider = nexaMasterProvider;
        this.heartbeatTimeout = DurationStyle.detectAndParse(heartbeatTimeout);
    }

    private NexaMaster nexaMaster() {
        return nexaMasterProvider.getObject();
    }

    /**
     * L1 注册认证：节点必须已录入 nexa_node（token 存 sha256），校验通过才接受注册。
     */
    @Override
    public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
        NexaNode node = nodeMapper.selectById(req.getRunnerId());
        if (node == null) {
            log.warn("[Master] 拒绝注册：节点未登记 runnerId={}, hostname={}",
                    req.getRunnerId(), req.getHostname());
            return reject(session, "节点未登记，请先录入注册令牌");
        }
        String presented = req.getToken();
        if (presented == null || presented.isBlank()
                || !node.getToken().equalsIgnoreCase(DigestUtil.sha256Hex(presented))) {
            log.warn("[Master] 拒绝注册：token 无效 runnerId={}", req.getRunnerId());
            return reject(session, "注册令牌无效");
        }
        node.setStatus("online");
        node.setLastHeartbeat(LocalDateTime.now());
        nodeMapper.updateById(node);
        log.info("[Master] 子节点注册: runnerId={}, hostname={}, ip={}, version={}",
                req.getRunnerId(), req.getHostname(), req.getIp(), req.getVersion());
        return RegisterResponse.newBuilder()
                .setSuccess(true)
                .setMessage("ok")
                .build();
    }

    /**
     * 拒绝注册：摘除会话 + 关闭连接 + 同步离线状态。
     *
     * <p>协议侧 RegisterHandler 的顺序是「先注册会话（并关闭同 ID 旧会话）、再回调 onRegister」，
     * 所以这里两步都要做：
     * <ol>
     *   <li>{@code removeIfPresent} 从会话表摘除本次连接：否则它稍后的 channelInactive 会被
     *       MasterChannelHandler 判定为“当前会话断开”，进而触发 onDisconnect 去失败化同 ID
     *       合法节点的待处理任务、并把节点误标离线；</li>
     *   <li>{@code close} 关闭连接：否则被拒节点仍持有可用会话，能接收任务下发、拉取产物。</li>
     * </ol>
     * 注：被拒连接顶掉同 ID 合法会话的问题根源在协议侧顺序（计划步骤 1），此处只做兜底收敛。
     */
    private RegisterResponse reject(RunnerSession session, String message) {
        String runnerId = session.getRunnerId();
        try {
            nexaMaster().getSessionManager().removeIfPresent(runnerId, session);
        } catch (Exception e) {
            log.warn("[Master] 摘除被拒会话异常: runnerId={}, err={}", runnerId, e.getMessage());
        }
        try {
            session.close();
        } catch (Exception e) {
            log.warn("[Master] 关闭被拒会话异常: runnerId={}, err={}", runnerId, e.getMessage());
        }
        nodeService.removeNode(runnerId);
        markNodeOffline(runnerId);
        return RegisterResponse.newBuilder()
                .setSuccess(false)
                .setMessage(message)
                .build();
    }

    @Override
    public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
        // L2：一律按会话身份记账，不采用报文中的 runnerId（协议侧当前按报文查找会话，
        // 一旦协议修正为按 channel 解析会话，这里的写法即为正确语义）
        String runnerId = session.getRunnerId();
        log.debug("[Master] 心跳: runnerId={}, runningTasks={}, cpuUsage={}, memoryUsage={}",
                runnerId, req.getRunningTasks(), req.getCpuUsage(), req.getMemoryUsage());
        nodeService.recordHeartbeat(runnerId, req.getRunningTasks(), req.getCpuUsage(), req.getMemoryUsage());
        NexaNode node = nodeMapper.selectById(runnerId);
        if (node != null) {
            node.setStatus("online");
            node.setLastHeartbeat(LocalDateTime.now());
            nodeMapper.updateById(node);
        }
    }

    /**
     * 子节点断开：失败化该节点待处理任务、清理负载与查询等待，并标记离线。
     *
     * <p>若该节点已有健康的新会话（刚刚重连成功），说明这是旧会话的迟到断开事件，
     * 直接跳过——否则会把新会话的任务误判失败、并把已恢复的节点标成离线。
     */
    @Override
    public void onDisconnect(String runnerId, String reason) {
        log.info("[Master] 子节点断开: runnerId={}, reason={}", runnerId, reason);
        if (isRecoveredSession(runnerId)) {
            log.info("[Master] 节点已有健康新会话，判定为旧会话迟到断开事件，跳过任务失败化与离线标记: runnerId={}, reason={}",
                    runnerId, reason);
            return;
        }
        remoteTaskManager.failTasksForNode(runnerId, reason);
        queryManager.failPendingForNode(runnerId, reason);
        nodeService.removeNode(runnerId);
        markNodeOffline(runnerId);
    }

    /**
     * 该 runnerId 是否已有“健康的新会话”——用于识别旧会话的迟到断开事件。
     *
     * <p>判据：当前绑定会话存在、channel 活跃、且心跳未过期（说明刚注册或刚心跳）。
     * 不能只用 {@link NodeService#isOnline}：心跳超时路径下被关闭的会话瞬时可能仍显示 active，
     * 而它的心跳必然已过期；用心跳是否过期判定，两种情况都不会误跳过正常断开处理：
     * <ul>
     *   <li>连接断开（connection_lost）：协议侧已先移除会话，此处查不到 → 正常处理</li>
     *   <li>心跳超时（heartbeat_timeout）：会话心跳已过期 → 正常处理</li>
     *   <li>旧会话迟到事件 + 新会话健康：心跳新鲜 → 跳过</li>
     * </ul>
     */
    private boolean isRecoveredSession(String runnerId) {
        long timeoutMs = heartbeatTimeout.toMillis();
        return nodeService.getSession(runnerId)
                .filter(RunnerSession::isActive)
                .filter(session -> !session.isExpired(timeoutMs))
                .isPresent();
    }

    /**
     * 标记节点持久化状态为离线（最后已知状态快照；接口展示以实时会话为准）
     */
    private void markNodeOffline(String runnerId) {
        try {
            NexaNode node = nodeMapper.selectById(runnerId);
            if (node != null && !"offline".equals(node.getStatus())) {
                node.setStatus("offline");
                nodeMapper.updateById(node);
            }
        } catch (Exception e) {
            log.warn("[Master] 更新节点离线状态异常: runnerId={}, err={}", runnerId, e.getMessage());
        }
    }

    /**
     * 任务回执：交 RemoteTaskManager，并附上会话 runnerId 供 L2 身份校验
     */
    @Override
    public void onTaskResult(RunnerSession session, TaskResponse resp) {
        remoteTaskManager.onTaskResult(resp, session.getRunnerId());
    }

    @Override
    public void onContainerStatus(RunnerSession session, ContainerStatusResponse resp) {
        queryManager.onContainerStatus(session, resp);
    }

    @Override
    public void onContainerLogs(RunnerSession session, ContainerLogsResponse resp) {
        queryManager.onContainerLogs(session, resp);
    }

    /**
     * 子节点产物请求（ARTIFACT_REQ）：交 ArtifactTransferManager（含 L2 归属校验 + 分块下发）
     */
    @Override
    public void onArtifactRequest(RunnerSession session, Envelope requestEnvelope, ArtifactRequest req) {
        artifactTransferManager.handleArtifactRequest(session, requestEnvelope, req);
    }
}
