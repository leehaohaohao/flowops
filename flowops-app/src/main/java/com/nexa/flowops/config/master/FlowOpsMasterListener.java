package com.nexa.flowops.config.master;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.service.artifact.ArtifactTransferManager;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.flowops.service.node.RemoteTaskManager;
import com.nexa.flowops.service.node.SessionTracker;
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

/**
 * 主节点侧协议事件处理：注册认证（L1）、心跳、断开、任务/查询回执、产物请求，以及会话身份校验（L2）。
 *
 * <p>与协议 v0.6.1 / v0.6.2 的契约（见 docs/2026-09-23-runner-connection-recovery-plan.md 步骤 4）：
 * <ul>
 *   <li><b>先认证、后接管</b>：{@code onRegister} 回调发生在会话注册之前，失败响应由协议侧
 *       「先 flush 再 CLOSE」送达。因此拒绝路径不得关闭连接、不得操作会话表、不得影响
 *       同 runnerId 的合法在线会话</li>
 *   <li><b>已注册消息按发送连接鉴权</b>：协议侧经 {@code SessionResolver} 解析发送方当前会话，
 *       未注册连接、已被接管的旧连接、冒用他人身份的报文一律不会进入回调。
 *       因此本类按会话身份记账即可获得正确语义</li>
 *   <li><b>断开事件仅通知确认移除的当前会话</b>（连接断开 / 心跳超时 / 主动断开三条路径统一）。
 *       v0.6.2 起事件所有权为「谁成功条件移除当前会话，谁负责通知恰好一次」，
 *       上报原因按会话是否被标记超时决定。由于“移除旧会话 → 回调执行”之间仍可能完成新会话注册，
 *       断开清理<b>以协议会话注册表判定归属</b>（注册表中仍有会话即接管者，跳过清理），
 *       并放在按节点临界区内执行（D.2）。代次仅作诊断标签，不作归属比较</li>
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
    private final SessionTracker sessionTracker;

    public FlowOpsMasterListener(NodeService nodeService,
                                 RemoteTaskManager remoteTaskManager,
                                 QueryManager queryManager,
                                 NexaNodeMapper nodeMapper,
                                 ArtifactTransferManager artifactTransferManager,
                                 SessionTracker sessionTracker) {
        this.nodeService = nodeService;
        this.remoteTaskManager = remoteTaskManager;
        this.queryManager = queryManager;
        this.nodeMapper = nodeMapper;
        this.artifactTransferManager = artifactTransferManager;
        this.sessionTracker = sessionTracker;
    }

    /**
     * L1 注册认证：节点必须已录入 nexa_node（token 存 sha256），校验通过才接受注册。
     *
     * <p>协议 v0.6.0 起回调发生在「会话注册之前」，本方法只做判定与状态记账，
     * 不负责会话接管或连接关闭（由协议侧处理）。
     *
     * <p>认证通过后在<b>按节点临界区内</b>开启新会话代次、绑定到该连接，并写入在线状态：
     * 与断开路径的代次判定互斥，避免“判定仍为当前会话”之后又发生接管（见 D.2）。
     */
    @Override
    public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
        String runnerId = req.getRunnerId();
        NexaNode node = nodeMapper.selectById(runnerId);
        if (node == null) {
            log.warn("[Master] 拒绝注册：节点未登记 runnerId={}, hostname={}",
                    runnerId, req.getHostname());
            return reject(runnerId, "节点未登记，请先录入注册令牌");
        }
        String presented = req.getToken();
        if (presented == null || presented.isBlank()
                || !node.getToken().equalsIgnoreCase(DigestUtil.sha256Hex(presented))) {
            log.warn("[Master] 拒绝注册：token 无效 runnerId={}", runnerId);
            return reject(runnerId, "注册令牌无效");
        }
        return sessionTracker.withRunnerLock(runnerId, () -> {
            long generation = sessionTracker.beginSession();
            sessionTracker.bindGeneration(session, generation);
            node.setStatus("online");
            node.setLastHeartbeat(LocalDateTime.now());
            nodeMapper.updateById(node);
            log.info("[Master] 子节点注册: runnerId={}, hostname={}, ip={}, version={}, generation={}",
                    runnerId, req.getHostname(), req.getIp(), req.getVersion(), generation);
            return RegisterResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("ok")
                    .build();
        });
    }

    /**
     * 拒绝注册（协议 v0.6.0：先认证、后接管）。
     *
     * <p>v0.6.0 的 RegisterHandler 在回调本方法时**尚未注册会话、也未绑定连接身份**，
     * 且失败响应由协议侧「先 flush 再 CLOSE」负责送达。因此这里必须克制：
     * <ul>
     *   <li><b>不关闭连接</b>：否则会抢在失败响应写出前断开，客户端只能看到连接错误、
     *       拿不到“未登记 / 令牌无效”这类拒绝原因</li>
     *   <li><b>不操作会话表</b>：本连接未被注册，摘除动作只可能误伤同 runnerId 的合法在线会话</li>
     * </ul>
     * 仅做与在线状态无关的本地纠偏：该节点确实没有活跃会话时，把状态快照校正为离线。
     * （v0.6.0 前协议是“先接管后认证”，后端曾需在此摘除并关闭会话；依赖升级后该兜底已不再需要。）
     */
    private RegisterResponse reject(String runnerId, String message) {
        if (!nodeService.isOnline(runnerId)) {
            nodeService.removeNode(runnerId);
            markNodeOffline(runnerId);
        }
        return RegisterResponse.newBuilder()
                .setSuccess(false)
                .setMessage(message)
                .build();
    }

    @Override
    public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
        // L2：按会话身份记账。协议 v0.6.1 起心跳等已注册消息一律按“发送连接绑定的当前会话”解析，
        // 报文里的 runnerId 仅作一致性检查，因此这里取得的身份可信
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
     * 子节点断开（协议 v0.6.1 推荐入口，携带会话身份）：
     * 失败化该节点待处理任务、清理负载与查询等待，并标记离线。
     *
     * <p>协议保证「条件移除当前会话成功才通知」，但“移除旧会话 → 回调执行”之间仍可能
     * 完成新会话注册（D.2）。因此按<b>协议会话注册表</b>判定归属，而不是按代次分配顺序：
     * 事件到达时事件会话已被移除，注册表中若仍有会话，它必然是接管者，此时跳过清理，
     * 新会话的任务/查询不会被失败化、节点也不会被误标离线。
     *
     * <p>不能改用“代次比较”：本类 {@code onRegister} 回调发生在协议把会话写入注册表<b>之前</b>，
     * 两个同 ID 连接并发注册时，代次分配顺序可能与最终生效的会话顺序相反，
     * 从而把真正在线的会话误判为旧会话（导致漏清理）。
     */
    @Override
    public void onDisconnect(RunnerSession session, String reason) {
        handleDisconnect(session.getRunnerId(), sessionTracker.generationOf(session), reason);
    }

    /**
     * 子节点断开（旧签名，无会话身份）：同样以会话注册表判定——注册表为空说明节点确实已离线，
     * 按最后已知状态清理；若仍有会话则跳过（该事件不可归属，宁可交给超时清扫兜底）。
     */
    @Override
    public void onDisconnect(String runnerId, String reason) {
        handleDisconnect(runnerId, 0L, reason);
    }

    /**
     * 断开处理：归属判定与清理在同一个临界区内完成，避免重复清理与 DB 状态写入乱序。
     *
     * <p><b>残余窗口与兜底</b>：协议写会话注册表不在本临界区内，因此“读到注册表为空 → 执行清理”
     * 之间仍可能有新会话注册成功（D.2）。两道兜底：
     * <ul>
     *   <li>任务/查询失败化按<b>会话代次</b>归因（只失败化事件所属代次的工作），
     *       接管者刚下发的工作不会被误失败化</li>
     *   <li>离线快照写入后<b>立即复查注册表</b>，若已有接管者则修正回在线，
     *       把“在线会话对应离线快照”的窗口压到两次写入之间（且接口展示本就以实时会话为准）</li>
     * </ul>
     *
     * @param generation 事件会话的代次标签；0 表示身份未知（旧签名/未绑定）
     */
    private void handleDisconnect(String runnerId, long generation, String reason) {
        sessionTracker.runWithRunnerLock(runnerId, () -> {
            RunnerSession live = nodeService.getSession(runnerId).orElse(null);
            if (live != null) {
                // 事件会话已被协议条件移除，注册表里仍有会话 ⇒ 已被同 ID 新连接接管
                log.info("[Master] 跳过已接管旧会话的断开事件: runnerId={}, eventGeneration={}, liveGeneration={}, reason={}",
                        runnerId, generation, sessionTracker.generationOf(live), reason);
                return;
            }
            log.info("[Master] 子节点断开: runnerId={}, generation={}, reason={}", runnerId, generation, reason);
            remoteTaskManager.failTasksForNode(runnerId, generation, reason);
            queryManager.failPendingForNode(runnerId, generation, reason);
            nodeService.removeNode(runnerId);
            updateOfflineSnapshot(runnerId);
        });
    }

    /**
     * 写离线快照并复查：清理期间若已有新会话接管，立刻修正回在线
     * （快照为“最后已知状态”，权威在线状态由实时会话计算，见 NodeController.toVO）
     */
    private void updateOfflineSnapshot(String runnerId) {
        markNodeOffline(runnerId);
        if (nodeService.getSession(runnerId).isPresent()) {
            log.info("[Master] 离线快照写入后检测到新会话接管，修正为在线快照: runnerId={}", runnerId);
            markNodeOnline(runnerId);
        }
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
     * 标记节点持久化状态为在线（快照自愈用，不刷新心跳时间）
     */
    private void markNodeOnline(String runnerId) {
        try {
            NexaNode node = nodeMapper.selectById(runnerId);
            if (node != null && !"online".equals(node.getStatus())) {
                node.setStatus("online");
                nodeMapper.updateById(node);
            }
        } catch (Exception e) {
            log.warn("[Master] 更新节点在线状态异常: runnerId={}, err={}", runnerId, e.getMessage());
        }
    }

    /**
     * 任务回执：交 RemoteTaskManager，并附上会话 runnerId 供 L2 身份校验
     */
    @Override
    public void onTaskResult(RunnerSession session, TaskResponse resp) {
        // 下发路径在同一节点锁内发送并登记 pending，回执须等待登记完成。
        sessionTracker.runWithRunnerLock(session.getRunnerId(),
                () -> remoteTaskManager.onTaskResult(resp, session.getRunnerId()));
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
