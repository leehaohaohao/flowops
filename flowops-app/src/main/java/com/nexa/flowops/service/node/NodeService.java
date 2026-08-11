package com.nexa.flowops.service.node;

import com.nexa.flowops.dto.NodeInfoVO;
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

@Service
public class NodeService {

    /**
     * 通过 ObjectProvider 惰性获取 NexaMaster，避免与
     * nexaMaster(需 NexaMasterListener=FlowOpsMasterListener) 形成构造循环：
     * FlowOpsMasterListener → NodeService → NexaMaster → FlowOpsMasterListener
     */
    private final ObjectProvider<NexaMaster> nexaMasterProvider;

    /** runnerId -> 最近一次心跳上报的负载 */
    private final Map<String, NodeLoad> loads = new ConcurrentHashMap<>();

    public NodeService(ObjectProvider<NexaMaster> nexaMasterProvider) {
        this.nexaMasterProvider = nexaMasterProvider;
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
        return nexaMaster().getSessionManager().get(runnerId)
                .map(RunnerSession::isActive)
                .orElse(false);
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
