package com.nexa.flowops.config.master;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.service.artifact.ArtifactTransferManager;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.flowops.service.node.RemoteTaskManager;
import com.nexa.flowops.service.node.SessionTracker;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Task.TaskResponse;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主节点监听器的注册/断开/鉴权语义测试。
 *
 * <p>对应 docs/2026-09-23-runner-connection-recovery-plan.md 步骤 4 与 D.2：
 * <ul>
 *   <li>协议 v0.6.x <b>先认证后接管</b>：拒绝路径不得关闭连接、不得操作会话表、
 *       不得影响同 ID 合法在线会话</li>
 *   <li>协议 v0.6.2 <b>断开事件所有权</b>：谁成功移除当前会话谁通知恰好一次，原因按是否超时标记决定</li>
 *   <li><b>D.2 归属判定</b>：以协议会话注册表为准（注册表中仍有会话 ⇒ 接管者 ⇒ 跳过清理）。
 *       这里特意覆盖“代次分配顺序与最终生效会话顺序相反”的交错——后端 onRegister 回调先于协议写注册表，
 *       若按代次比较会把真正在线的会话误判为旧会话而漏清理</li>
 * </ul>
 */
class FlowOpsMasterListenerReconnectTest {

    private static final String RUNNER_ID = "runner-1";
    private static final String VALID_TOKEN = "right-token";

    private NodeService nodeService;
    private RemoteTaskManager remoteTaskManager;
    private QueryManager queryManager;
    private NexaNodeMapper nodeMapper;
    private SessionManager sessionManager;
    private SessionTracker sessionTracker;
    private NexaNode persistedNode;
    private Channel channel;
    private FlowOpsMasterListener listener;

    @BeforeEach
    void setUp() {
        nodeService = mock(NodeService.class);
        remoteTaskManager = mock(RemoteTaskManager.class);
        queryManager = mock(QueryManager.class);
        nodeMapper = mock(NexaNodeMapper.class);

        // 用同一个可变对象模拟数据库中的 nexa_node 行
        persistedNode = node(RUNNER_ID, DigestUtil.sha256Hex(VALID_TOKEN), "offline");
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(persistedNode);

        // 真实 SessionManager（充当协议会话注册表）+ SessionTracker
        sessionManager = new SessionManager();
        when(nodeService.getSession(anyString()))
                .thenAnswer(inv -> sessionManager.get(inv.getArgument(0)));
        when(nodeService.isOnline(anyString()))
                .thenAnswer(inv -> sessionManager.get(inv.getArgument(0))
                        .map(RunnerSession::isActive)
                        .orElse(false));

        sessionTracker = new SessionTracker();
        channel = activeChannel();
        listener = new FlowOpsMasterListener(nodeService, remoteTaskManager, queryManager,
                nodeMapper, mock(ArtifactTransferManager.class), sessionTracker);
    }

    // ==================== 注册与拒绝注册（先认证后接管） ====================

    @Test
    void rejectUnregisteredNode_returnsReasonWithoutTouchingSessionOrConnection() {
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(null);

        RegisterResponse resp = listener.onRegister(newSession(RUNNER_ID), registerRequest("any-token"));

        assertFalse(resp.getSuccess());
        assertTrue(resp.getMessage().contains("未登记"), "拒绝原因应清晰: " + resp.getMessage());
        assertTrue(sessionManager.get(RUNNER_ID).isEmpty(), "被拒连接不得被注册");
        assertTrue(channel.isOpen(), "后端不应关闭该连接（关闭由协议侧负责）");
        verify(nodeService).removeNode(RUNNER_ID);
        verify(nodeMapper, never()).updateById(any());
    }

    @Test
    void rejectInvalidToken_correctsStaleOnlineSnapshot() {
        persistedNode.setStatus("online"); // 库里快照残留 online，但该节点当前没有活跃会话

        RegisterResponse resp = listener.onRegister(newSession(RUNNER_ID), registerRequest("wrong-token"));

        assertFalse(resp.getSuccess());
        assertTrue(resp.getMessage().contains("令牌无效"));
        assertTrue(sessionManager.get(RUNNER_ID).isEmpty());
        assertTrue(channel.isOpen(), "后端不应关闭该连接（关闭由协议侧负责）");
        assertEquals("offline", persistedNode.getStatus(), "残留的在线快照应被校正");
    }

    @Test
    void rejectInvalidToken_doesNotDisturbExistingOnlineSession() {
        RunnerSession legit = newSession(RUNNER_ID, activeChannel());
        protocolRegister(legit); // 同 runnerId 的合法节点在线
        persistedNode.setStatus("online");

        Channel intruderChannel = activeChannel();
        RegisterResponse resp = listener.onRegister(newSession(RUNNER_ID, intruderChannel), registerRequest("wrong-token"));

        assertFalse(resp.getSuccess());
        assertSame(legit, sessionManager.get(RUNNER_ID).orElse(null), "合法在线会话必须保持不变");
        assertTrue(intruderChannel.isOpen(), "后端不应关闭入侵连接（关闭由协议侧负责）");
        verify(nodeMapper, never()).updateById(any()); // 节点仍在线，不得改写状态快照
        verify(remoteTaskManager, never()).failTasksForNode(anyString(), anyLong(), anyString());
        verify(queryManager, never()).failPendingForNode(anyString(), anyLong(), anyString());
    }

    @Test
    void acceptValidToken_marksNodeOnlineAndAssignsGenerationLabel() {
        RunnerSession session = newSession(RUNNER_ID);

        RegisterResponse resp = listener.onRegister(session, registerRequest(VALID_TOKEN));

        assertTrue(resp.getSuccess());
        assertTrue(channel.isOpen(), "后端不应关闭该连接");
        assertEquals("online", persistedNode.getStatus());
        assertTrue(sessionTracker.generationOf(session) > 0, "应为该连接分配代次标签");
    }

    // ==================== 断开归属（D.2） ====================

    @Test
    void liveSessionWithOlderGeneration_isStillCleanedUp() {
        // 交错：后端回调先分配代次、协议随后写注册表，两者顺序可能相反。
        // 这里 A 的代次标签更小，但协议最后注册的是 A —— A 才是真正在线的会话。
        RunnerSession sessionA = registerNode(activeChannel());
        RunnerSession sessionB = registerNode(activeChannel());
        assertTrue(sessionTracker.generationOf(sessionA) < sessionTracker.generationOf(sessionB),
                "前置：A 的代次标签更小");
        protocolRegister(sessionA); // A 最终生效，B 被顶掉
        assertSame(sessionA, sessionManager.get(RUNNER_ID).orElse(null),
                "前置：最终生效的是标签更小的 A（若按代次比较会误判为旧会话）");

        protocolRemove(sessionA); // 协议：先条件移除，再回调
        long generationA = sessionTracker.generationOf(sessionA);
        listener.onDisconnect(sessionA, "connection_lost");

        verify(remoteTaskManager).failTasksForNode(RUNNER_ID, generationA, "connection_lost");
        verify(queryManager).failPendingForNode(RUNNER_ID, generationA, "connection_lost");
        verify(nodeService).removeNode(RUNNER_ID);
        assertEquals("offline", persistedNode.getStatus(), "真正在线会话的断开必须完成清理");
    }

    @Test
    void takeoverBetweenRegistryCheckAndCleanup_scopesFailureAndRepairsSnapshot() {
        RunnerSession old = registerNode(activeChannel());
        long oldGeneration = sessionTracker.generationOf(old);
        protocolRegister(old);
        protocolRemove(old); // 事件到达前协议已完成条件移除

        RunnerSession successor = newSession(RUNNER_ID, activeChannel());
        long[] failureGeneration = new long[1];
        List<String> statusWrites = new ArrayList<>();

        // 固定交错：在「读注册表(空) → 执行清理」之间插入新会话注册成功。
        // 协议写会话注册表不在本类的按节点锁内，这正是 D.2 的残余窗口。
        doAnswer(inv -> {
            failureGeneration[0] = inv.getArgument(1);
            listener.onRegister(successor, registerRequest(VALID_TOKEN)); // 新会话注册成功：分配新代次并写在线
            protocolRegister(successor);                                  // 协议随后把会话写入注册表
            return null;
        }).when(remoteTaskManager).failTasksForNode(eq(RUNNER_ID), anyLong(), anyString());
        doAnswer(inv -> {
            statusWrites.add(persistedNode.getStatus()); // 记录每次落库时的状态
            return 1;
        }).when(nodeMapper).updateById(any(NexaNode.class));

        listener.onDisconnect(old, "connection_lost");

        assertEquals(oldGeneration, failureGeneration[0],
                "清理必须按事件会话代次归因——接管者刚下发的工作不会被失败化");
        assertSame(successor, sessionManager.get(RUNNER_ID).orElse(null), "接管者已写入会话注册表");
        assertTrue(statusWrites.contains("offline"), "离线快照确实被写入过");
        assertEquals("online", statusWrites.get(statusWrites.size() - 1),
                "写入离线后复查到新会话，应修正为在线快照");
    }

    @Test
    void supersededSession_isSkippedWhileSuccessorIsLive() {
        RunnerSession sessionA = registerNode(activeChannel());
        RunnerSession sessionB = registerNode(activeChannel());
        protocolRegister(sessionB); // B 成为当前会话，A 被顶掉

        // A 的迟到断开事件（现实中协议已不再通知被接管会话，此处为防御性覆盖）
        listener.onDisconnect(sessionA, "connection_lost");

        verify(remoteTaskManager, never()).failTasksForNode(anyString(), anyLong(), anyString());
        verify(queryManager, never()).failPendingForNode(anyString(), anyLong(), anyString());
        verify(nodeService, never()).removeNode(anyString());
        assertEquals("online", persistedNode.getStatus(), "被接管的节点仍应在线");
    }

    @Test
    void currentSessionDisconnect_failsWorkAndMarksOffline() {
        RunnerSession session = registerNode(activeChannel());
        long generation = sessionTracker.generationOf(session);
        protocolRegister(session);
        protocolRemove(session);

        listener.onDisconnect(session, "connection_lost");

        verify(remoteTaskManager).failTasksForNode(RUNNER_ID, generation, "connection_lost");
        verify(queryManager).failPendingForNode(RUNNER_ID, generation, "connection_lost");
        verify(nodeService).removeNode(RUNNER_ID);
        assertEquals("offline", persistedNode.getStatus());
    }

    @Test
    void heartbeatTimeoutOfCurrentSession_failsWorkAndMarksOffline() {
        RunnerSession session = registerNode(activeChannel());
        long generation = sessionTracker.generationOf(session);
        protocolRegister(session);
        protocolRemove(session);

        // 协议 v0.6.2：成功移除者按会话状态上报原因，超时路径即为 heartbeat_timeout
        listener.onDisconnect(session, "heartbeat_timeout");

        verify(remoteTaskManager).failTasksForNode(RUNNER_ID, generation, "heartbeat_timeout");
        verify(queryManager).failPendingForNode(RUNNER_ID, generation, "heartbeat_timeout");
        assertEquals("offline", persistedNode.getStatus());
    }

    @Test
    void legacySignature_skipsCleanupWhileNodeStillOnline() {
        RunnerSession session = registerNode(activeChannel());
        protocolRegister(session);

        listener.onDisconnect(RUNNER_ID, "connection_lost");

        verify(remoteTaskManager, never()).failTasksForNode(anyString(), anyLong(), anyString());
        verify(queryManager, never()).failPendingForNode(anyString(), anyLong(), anyString());
        verify(nodeService, never()).removeNode(anyString());
        verify(nodeMapper, times(1)).updateById(any()); // 仅注册写入
        assertEquals("online", persistedNode.getStatus());
    }

    @Test
    void legacySignature_cleansUpWhenNodeHasNoActiveSession() {
        RunnerSession session = registerNode(activeChannel());
        protocolRegister(session);
        protocolRemove(session);

        listener.onDisconnect(RUNNER_ID, "connection_lost");

        // 旧签名无会话身份：以 0 代次委派，由下层按“身份未知”保守处理（不失败化任何工作）
        verify(remoteTaskManager).failTasksForNode(RUNNER_ID, 0L, "connection_lost");
        verify(queryManager).failPendingForNode(RUNNER_ID, 0L, "connection_lost");
        verify(nodeService).removeNode(RUNNER_ID);
        assertEquals("offline", persistedNode.getStatus());
    }

    @Test
    void sessionWithoutBoundGeneration_stillUsesSessionRegistry() {
        // 未绑定代次的会话：身份标签未知，但归属仍以注册表为准——注册表里仍有会话 ⇒ 跳过
        protocolRegister(newSession(RUNNER_ID));

        listener.onDisconnect(newSession(RUNNER_ID), "connection_lost");

        verify(remoteTaskManager, never()).failTasksForNode(anyString(), anyLong(), anyString());
        verify(nodeMapper, never()).updateById(any());
    }

    // ==================== 会话身份（L2） ====================

    @Test
    void heartbeat_recordsStateBySessionIdentityNotPayloadRunnerId() {
        RunnerSession session = newSession(RUNNER_ID);
        HeartbeatRequest req = HeartbeatRequest.newBuilder()
                .setRunnerId("runner-2") // 报文伪报其他节点（协议侧已按连接会话鉴权，此处仅验证记账口径）
                .setRunningTasks(3)
                .setCpuUsage(10.0)
                .setMemoryUsage(20.0)
                .build();

        listener.onHeartbeat(session, req);

        verify(nodeService).recordHeartbeat(RUNNER_ID, 3, 10.0, 20.0);
        verify(nodeService, never()).recordHeartbeat(eq("runner-2"), anyInt(), anyDouble(), anyDouble());
        verify(nodeMapper).selectById(RUNNER_ID);
    }

    @Test
    void taskResult_passesSessionRunnerIdForIdentityCheck() {
        RunnerSession session = newSession(RUNNER_ID);
        TaskResponse resp = TaskResponse.newBuilder()
                .setTaskId("task-1")
                .setRunnerId("runner-2") // 报文伪报其他节点
                .setSuccess(true)
                .build();

        listener.onTaskResult(session, resp);

        verify(remoteTaskManager).onTaskResult(resp, RUNNER_ID);
    }

    // ==================== 工具 ====================

    /** 走一次后端注册流程：分配代次标签、绑定到连接、置为在线 */
    private RunnerSession registerNode(Channel ch) {
        RunnerSession session = newSession(RUNNER_ID, ch);
        RegisterResponse resp = listener.onRegister(session, registerRequest(VALID_TOKEN));
        assertTrue(resp.getSuccess(), "测试前置：注册应成功");
        return session;
    }

    /** 模拟协议把该会话写入会话注册表 */
    private void protocolRegister(RunnerSession session) {
        sessionManager.register(session);
    }

    /** 模拟协议条件移除该会话（断开通知前必然发生） */
    private void protocolRemove(RunnerSession session) {
        sessionManager.removeIfPresent(session.getRunnerId(), session);
    }

    private Channel activeChannel() {
        // 用真实 channel：需要可用的 channel 属性（绑定会话代次）与 isActive/isOpen 语义
        return new EmbeddedChannel();
    }

    private RunnerSession newSession(String runnerId) {
        return newSession(runnerId, channel);
    }

    private RunnerSession newSession(String runnerId, Channel ch) {
        return new RunnerSession(runnerId, ch, "test-host", "127.0.0.1", "0.6.2");
    }

    private RegisterRequest registerRequest(String token) {
        return RegisterRequest.newBuilder()
                .setRunnerId(RUNNER_ID)
                .setHostname("test-host")
                .setIp("127.0.0.1")
                .setVersion("0.6.2")
                .setToken(token)
                .build();
    }

    private NexaNode node(String runnerId, String tokenHash, String status) {
        NexaNode node = new NexaNode();
        node.setRunnerId(runnerId);
        node.setToken(tokenHash);
        node.setStatus(status);
        return node;
    }
}
