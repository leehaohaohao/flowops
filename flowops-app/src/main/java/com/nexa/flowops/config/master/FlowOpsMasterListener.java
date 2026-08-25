package com.nexa.flowops.config.master;

import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.flowops.service.node.RemoteTaskManager;
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

@Component
public class FlowOpsMasterListener implements NexaMasterListener {

    private static final Logger log = LoggerFactory.getLogger(FlowOpsMasterListener.class);

    private final NodeService nodeService;
    private final RemoteTaskManager remoteTaskManager;
    private final QueryManager queryManager;

    public FlowOpsMasterListener(NodeService nodeService,
                                 RemoteTaskManager remoteTaskManager,
                                 QueryManager queryManager) {
        this.nodeService = nodeService;
        this.remoteTaskManager = remoteTaskManager;
        this.queryManager = queryManager;
    }

    @Override
    public RegisterResponse onRegister(RunnerSession session, RegisterRequest req) {
        log.info("[Master] 子节点注册: runnerId={}, hostname={}, ip={}, version={}",
                req.getRunnerId(), req.getHostname(), req.getIp(), req.getVersion());
        return RegisterResponse.newBuilder()
                .setSuccess(true)
                .setMessage("ok")
                .build();
    }

    @Override
    public void onHeartbeat(RunnerSession session, HeartbeatRequest req) {
        log.debug("[Master] 心跳: runnerId={}, runningTasks={}, cpuUsage={}, memoryUsage={}",
                req.getRunnerId(), req.getRunningTasks(), req.getCpuUsage(), req.getMemoryUsage());
        nodeService.recordHeartbeat(req.getRunnerId(), req.getRunningTasks(), req.getCpuUsage(), req.getMemoryUsage());
    }

    @Override
    public void onDisconnect(String runnerId, String reason) {
        log.info("[Master] 子节点断开: runnerId={}, reason={}", runnerId, reason);
        remoteTaskManager.failTasksForNode(runnerId, reason);
        queryManager.failPendingForNode(runnerId, reason);
        nodeService.removeNode(runnerId);
    }

    @Override
    public void onTaskResult(RunnerSession session, TaskResponse resp) {
        remoteTaskManager.onTaskResult(resp);
    }

    @Override
    public void onContainerStatus(RunnerSession session, ContainerStatusResponse resp) {
        queryManager.onContainerStatus(session, resp);
    }

    @Override
    public void onContainerLogs(RunnerSession session, ContainerLogsResponse resp) {
        queryManager.onContainerLogs(session, resp);
    }
}
