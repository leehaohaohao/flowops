package com.nexa.flowops.service.node;

import com.nexa.flowops.dto.NodeInfoVO;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.RunnerSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class NodeService {

    /**
     * 通过 ObjectProvider 惰性获取 NexaMaster，避免与
     * nexaMaster(需 NexaMasterListener=FlowOpsMasterListener) 形成构造循环：
     * FlowOpsMasterListener → NodeService → NexaMaster → FlowOpsMasterListener
     */
    private final ObjectProvider<NexaMaster> nexaMasterProvider;
    private final SessionTracker sessionTracker;

    /** runnerId -> 最近一次心跳上报的负载 */
    private final Map<String, NodeLoad> loads = new ConcurrentHashMap<>();

    public NodeService(ObjectProvider<NexaMaster> nexaMasterProvider, SessionTracker sessionTracker) {
        this.nexaMasterProvider = nexaMasterProvider;
        this.sessionTracker = sessionTracker;
    }

    private NexaMaster nexaMaster() {
        return nexaMasterProvider.getObject();
    }

    public List<NodeInfoVO> getOnlineRunners() {
        List<NodeInfoVO> list = new ArrayList<>();
        for (RunnerSession session : nexaMaster().getSessionManager().allSessions()) {
            list.add(toNodeInfo(session));
        }
        return list;
    }

    public Optional<NodeInfoVO> getRunnerStatus(String runnerId) {
        return nexaMaster().getSessionManager().get(runnerId)
                .map(this::toNodeInfo);
    }

    public int getOnlineCount() {
        return nexaMaster().getOnlineCount();
    }

    /**
     * 记录子节点最近一次心跳负载，供自动调度使用
     */
    public void recordHeartbeat(String runnerId, int runningTasks, double cpuUsage, double memoryUsage) {
        loads.put(runnerId, new NodeLoad(runningTasks, cpuUsage, memoryUsage));
    }

    public void removeNode(String runnerId) {
        loads.remove(runnerId);
    }

    public boolean isOnline(String runnerId) {
        return getSession(runnerId)
                .map(RunnerSession::isActive)
                .orElse(false);
    }

    /**
     * 当前 runnerId 绑定的会话（可能为空）。
     * 供重连场景判定“是否已有健康的新会话”，识别旧会话的迟到断开事件。
     */
    public Optional<RunnerSession> getSession(String runnerId) {
        if (runnerId == null) {
            return Optional.empty();
        }
        return nexaMaster().getSessionManager().get(runnerId);
    }

    /** 只读取一次注册表，使发送连接与工作归属取自同一个会话。 */
    public Optional<SessionTarget> getCurrentTarget(String runnerId) {
        return getSession(runnerId)
                .map(session -> new SessionTarget(session, sessionTracker.generationOf(session)));
    }

    /** 工作登记与断开清理共用按节点锁。协议注册表写入仍可并发，故还需按会话归因。 */
    public <T> T withRunnerLock(String runnerId, Supplier<T> action) {
        return sessionTracker.withRunnerLock(runnerId, action);
    }

    public record SessionTarget(RunnerSession session, long generation) {
        public boolean send(Envelope envelope) {
            return session.send(envelope);
        }
    }

    /**
     * 最少负载调度：在在线节点中选 runningTasks 最小的一个。
     * 无在线节点返回 Optional.empty()。
     */
    public Optional<String> selectLeastLoaded() {
        return nexaMaster().getSessionManager().allSessions().stream()
                .filter(RunnerSession::isActive)
                .min(Comparator.comparingInt(session -> getRunningTasks(session.getRunnerId())))
                .map(RunnerSession::getRunnerId);
    }

    private int getRunningTasks(String runnerId) {
        NodeLoad load = loads.get(runnerId);
        return load != null ? load.runningTasks : 0;
    }

    private NodeInfoVO toNodeInfo(RunnerSession session) {
        NodeInfoVO vo = new NodeInfoVO();
        vo.setRunnerId(session.getRunnerId());
        vo.setHostname(session.getHostname());
        vo.setIp(session.getIp());
        vo.setVersion(session.getVersion());
        vo.setLastHeartbeatTime(session.getLastHeartbeatTime());
        vo.setOnline(session.isActive());

        NodeLoad load = loads.get(session.getRunnerId());
        if (load != null) {
            vo.setRunningTasks(load.runningTasks);
            vo.setCpuUsage(load.cpuUsage);
            vo.setMemoryUsage(load.memoryUsage);
        }
        return vo;
    }

    private record NodeLoad(int runningTasks, double cpuUsage, double memoryUsage) {
    }
}
