package com.nexa.flowops.config.master;

import com.nexa.flowops.common.util.DigestUtil;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.service.artifact.ArtifactTransferManager;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.flowops.service.node.RemoteTaskManager;
import com.nexa.protocol.Heartbeat.HeartbeatRequest;
import com.nexa.protocol.Register.RegisterRequest;
import com.nexa.protocol.Register.RegisterResponse;
import com.nexa.protocol.Task.TaskResponse;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.RunnerSession;
import com.nexa.protocol.master.SessionManager;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重连事件语义测试（对应 docs/2026-09-23-runner-connection-recovery-plan.md 步骤 4）。
 *
 * <p>覆盖三件事：
 * <ul>
 *   <li>拒绝注册必须摘除并关闭会话，且不残留“当前会话”去触发后续 onDisconnect</li>
 *   <li>旧会话迟到断开事件不得失败化新会话任务、不得把已恢复节点标离线；
 *       而正常的连接断开 / 心跳超时仍必须失败化任务并标记离线</li>
 *   <li>心跳、任务回执一律按会话身份记账（L2），不信任报文中的 runnerId</li>
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
    private Channel channel;
    private ObjectProvider<NexaMaster> masterProvider;
    private FlowOpsMasterListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        nodeService = mock(NodeService.class);
        remoteTaskManager = mock(RemoteTaskManager.class);
        queryManager = mock(QueryManager.class);
        nodeMapper = mock(NexaNodeMapper.class);

        // 使用真实 SessionManager，验证生产语义（协议侧会先 register 再回调 onRegister）
        sessionManager = new SessionManager();
        NexaMaster master = mock(NexaMaster.class);
        when(master.getSessionManager()).thenReturn(sessionManager);
        masterProvider = mock(ObjectProvider.class);
        when(masterProvider.getObject()).thenReturn(master);

        when(nodeService.getSession(anyString())).thenReturn(Optional.empty());

        channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);

        listener = buildListener("30s");
    }

    /** 按指定心跳超时构建被测 listener（超时值支持 "30s" / "10ms" 等 Boot 时长写法） */
    private FlowOpsMasterListener buildListener(String heartbeatTimeout) {
        return new FlowOpsMasterListener(nodeService, remoteTaskManager, queryManager,
                nodeMapper, mock(ArtifactTransferManager.class), masterProvider, heartbeatTimeout);
    }

    // ==================== 注册与拒绝注册 ====================

    @Test
    void rejectUnregisteredNode_deregistersSessionAndClosesConnection() {
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(null);
        RunnerSession session = newSession(RUNNER_ID);
        sessionManager.register(session); // 协议侧先注册会话，再回调 onRegister

        RegisterResponse resp = listener.onRegister(session, registerRequest("any-token"));

        assertFalse(resp.getSuccess());
        assertTrue(resp.getMessage().contains("未登记"), "拒绝原因应清晰: " + resp.getMessage());
        assertTrue(sessionManager.get(RUNNER_ID).isEmpty(), "被拒会话必须从会话表摘除");
        verify(channel).close();
        verify(nodeService).removeNode(RUNNER_ID);
        verify(nodeMapper, never()).updateById(any());
    }

    @Test
    void rejectInvalidToken_deregistersSessionAndMarksNodeOffline() {
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(node(RUNNER_ID, DigestUtil.sha256Hex(VALID_TOKEN), "online"));
        RunnerSession session = newSession(RUNNER_ID);
        sessionManager.register(session);

        RegisterResponse resp = listener.onRegister(session, registerRequest("wrong-token"));

        assertFalse(resp.getSuccess());
        assertTrue(resp.getMessage().contains("令牌无效"));
        assertTrue(sessionManager.get(RUNNER_ID).isEmpty());
        verify(channel).close();
        assertEquals("offline", capturedNodeStatus());
    }

    @Test
    void acceptValidToken_keepsSessionAndMarksNodeOnline() {
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(node(RUNNER_ID, DigestUtil.sha256Hex(VALID_TOKEN), "offline"));
        RunnerSession session = newSession(RUNNER_ID);
        sessionManager.register(session);

        RegisterResponse resp = listener.onRegister(session, registerRequest(VALID_TOKEN));

        assertTrue(resp.getSuccess());
        assertTrue(sessionManager.get(RUNNER_ID).isPresent(), "合法注册不得摘除会话");
        verify(channel, never()).close();
        assertEquals("online", capturedNodeStatus());
    }

    // ==================== 断开事件 ====================

    @Test
    void staleDisconnectWithHealthyNewSession_doesNotTouchTasksOrStatus() {
        // 节点已重连：会话表里是刚注册、心跳新鲜的新会话
        RunnerSession newSession = newSession(RUNNER_ID);
        sessionManager.register(newSession);
        when(nodeService.getSession(RUNNER_ID)).thenReturn(Optional.of(newSession));

        // 旧会话迟到的断开事件
        listener.onDisconnect(RUNNER_ID, "heartbeat_timeout");

        verify(remoteTaskManager, never()).failTasksForNode(anyString(), anyString());
        verify(queryManager, never()).failPendingForNode(anyString(), anyString());
        verify(nodeService, never()).removeNode(anyString());
        verify(nodeMapper, never()).updateById(any());
    }

    @Test
    void disconnectWithoutSession_failsTasksAndMarksOffline() {
        // 连接断开路径：协议侧已先移除会话，因此查不到绑定会话
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(node(RUNNER_ID, "hash", "online"));

        listener.onDisconnect(RUNNER_ID, "connection_lost");

        verify(remoteTaskManager).failTasksForNode(RUNNER_ID, "connection_lost");
        verify(queryManager).failPendingForNode(RUNNER_ID, "connection_lost");
        verify(nodeService).removeNode(RUNNER_ID);
        assertEquals("offline", capturedNodeStatus());
    }

    @Test
    void heartbeatTimeoutWithExpiredSession_stillFailsTasksAndMarksOffline() throws Exception {
        // 心跳超时路径：会话可能仍绑定在表里、channel 也还 active，但心跳必然已过期
        listener = buildListener("10ms");
        RunnerSession stale = newSession(RUNNER_ID);
        sessionManager.register(stale);
        when(nodeService.getSession(RUNNER_ID)).thenReturn(Optional.of(stale));
        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(node(RUNNER_ID, "hash", "online"));
        Thread.sleep(40);

        listener.onDisconnect(RUNNER_ID, "heartbeat_timeout");

        verify(remoteTaskManager).failTasksForNode(RUNNER_ID, "heartbeat_timeout");
        verify(queryManager).failPendingForNode(RUNNER_ID, "heartbeat_timeout");
        verify(nodeService).removeNode(RUNNER_ID);
        assertEquals("offline", capturedNodeStatus());
    }

    // ==================== 会话身份（L2） ====================

    @Test
    void heartbeat_recordsStateBySessionIdentityNotPayloadRunnerId() {
        RunnerSession session = newSession(RUNNER_ID);
        HeartbeatRequest req = HeartbeatRequest.newBuilder()
                .setRunnerId("runner-2") // 报文伪报其他节点
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

    private RunnerSession newSession(String runnerId) {
        return new RunnerSession(runnerId, channel, "test-host", "127.0.0.1", "0.5.0");
    }

    private RegisterRequest registerRequest(String token) {
        return RegisterRequest.newBuilder()
                .setRunnerId(RUNNER_ID)
                .setHostname("test-host")
                .setIp("127.0.0.1")
                .setVersion("0.5.0")
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

    /** 捕获最后一次节点落库的状态 */
    private String capturedNodeStatus() {
        ArgumentCaptor<NexaNode> captor = ArgumentCaptor.forClass(NexaNode.class);
        verify(nodeMapper).updateById(captor.capture());
        return captor.getValue().getStatus();
    }
}
