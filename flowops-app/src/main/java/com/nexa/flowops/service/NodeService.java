package com.nexa.flowops.service;

import com.nexa.flowops.dto.NodeInfoVO;
import com.nexa.protocol.master.NexaMaster;
import com.nexa.protocol.master.RunnerSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NodeService {

    private final NexaMaster nexaMaster;

    public NodeService(NexaMaster nexaMaster) {
        this.nexaMaster = nexaMaster;
    }

    public List<NodeInfoVO> getOnlineRunners() {
        List<NodeInfoVO> list = new ArrayList<>();
        for (RunnerSession session : nexaMaster.getSessionManager().allSessions()) {
            list.add(toNodeInfo(session));
        }
        return list;
    }

    public Optional<NodeInfoVO> getRunnerStatus(String runnerId) {
        return nexaMaster.getSessionManager().get(runnerId)
                .map(this::toNodeInfo);
    }

    public int getOnlineCount() {
        return nexaMaster.getOnlineCount();
    }

    private NodeInfoVO toNodeInfo(RunnerSession session) {
        NodeInfoVO vo = new NodeInfoVO();
        vo.setRunnerId(session.getRunnerId());
        vo.setHostname(session.getHostname());
        vo.setIp(session.getIp());
        vo.setVersion(session.getVersion());
        vo.setLastHeartbeatTime(session.getLastHeartbeatTime());
        vo.setOnline(session.isActive());
        return vo;
    }
}
