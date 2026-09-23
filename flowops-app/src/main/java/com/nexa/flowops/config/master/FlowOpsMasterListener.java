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
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class FlowOpsMasterListener implements NexaMasterListener {

    private static final Logger log = LoggerFactory.getLogger(FlowOpsMasterListener.class);

    private final NodeService nodeService;
    private final RemoteTaskManager remoteTaskManager;
    private final QueryManager queryManager;
    private final NexaNodeMapper nodeMapper;
    private final ArtifactTransferManager artifactTransferManager;

    public FlowOpsMasterListener(NodeService nodeService,
                                 RemoteTaskManager remoteTaskManager,
                                 QueryManager queryManager,
                                 NexaNodeMapper nodeMapper,
                                 ArtifactTransferManager artifactTransferManager) {
        this.nodeService = nodeService;
        this.remoteTaskManager = remoteTaskManager;
        this.queryManager = queryManager;
        this.nodeMapper = nodeMapper;
        this.artifactTransferManager = artifactTransferManager;
    }

    /**
     * L1 注册认证：节点必须已录入 nexa_node（token 存 sha256），校验通过才接受注册。
     *
     * <p>注意：协议侧 RegisterHandler 是「先注册会话、再回调 onRegister」，因此拒绝时
     * 必须主动 {@link RunnerSession#close()} 关闭连接——否则被拒节点仍持有有效会话，
     * 依旧能收到任务下发 / 拉取产物，认证形同虚设。
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
     * 拒绝注册：返回失败响应并关闭会话（认证未通过不允许保留连接）
     */
    private RegisterResponse reject(RunnerSession session, String message) {
        try {
            session.close();
        } catch (Exception e) {
            log.warn("[Master] 关闭被拒会话异常: runnerId={}, err={}", session.getRunnerId(), e.getMessage());
        }
        nodeService.removeNode(session.getRunnerId());
        return RegisterResponse.newBuilder()
                .setSuccess(false)
                .setMessage(message)
                .build();
    }

    @Override
    public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
        log.debug("[Master] 心跳: runnerId={}, runningTasks={}, cpuUsage={}, memoryUsage={}",
                req.getRunnerId(), req.getRunningTasks(), req.getCpuUsage(), req.getMemoryUsage());
        nodeService.recordHeartbeat(req.getRunnerId(), req.getRunningTasks(), req.getCpuUsage(), req.getMemoryUsage());
        NexaNode node = nodeMapper.selectById(req.getRunnerId());
        if (node != null) {
            node.setStatus("online");
            node.setLastHeartbeat(LocalDateTime.now());
            nodeMapper.updateById(node);
        }
    }

    @Override
    public void onDisconnect(String runnerId, String reason) {
        log.info("[Master] 子节点断开: runnerId={}, reason={}", runnerId, reason);
        remoteTaskManager.failTasksForNode(runnerId, reason);
        queryManager.failPendingForNode(runnerId, reason);
        nodeService.removeNode(runnerId);
        NexaNode node = nodeMapper.selectById(runnerId);
        if (node != null) {
            node.setStatus("offline");
            nodeMapper.updateById(node);
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
