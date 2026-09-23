package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.QueryManager;
import com.nexa.protocol.Query.ContainerStatusRequest;
import com.nexa.protocol.Query.ContainerStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 部署编排入口：负责「本机执行 / 远程下发」路由，具体执行分别委托给
 * LocalDeployRunner 与 RemoteDeployDispatcher。
 */
@Service
public class DeployExecutorService {

    private static final Logger log = LoggerFactory.getLogger(DeployExecutorService.class);

    private final DeployServiceMapper serviceMapper;
    private final UploadHelper uploadHelper;
    private final LocalDeployRunner localRunner;
    private final RemoteDeployDispatcher remoteDispatcher;
    private final DeployLogHelper logHelper;
    private final NodeService nodeService;
    private final QueryManager queryManager;

    public DeployExecutorService(DeployServiceMapper serviceMapper,
                                 UploadHelper uploadHelper,
                                 LocalDeployRunner localRunner,
                                 RemoteDeployDispatcher remoteDispatcher,
                                 DeployLogHelper logHelper,
                                 NodeService nodeService,
                                 QueryManager queryManager) {
        this.serviceMapper = serviceMapper;
        this.uploadHelper = uploadHelper;
        this.localRunner = localRunner;
        this.remoteDispatcher = remoteDispatcher;
        this.logHelper = logHelper;
        this.nodeService = nodeService;
        this.queryManager = queryManager;
    }

    // ==================== 上传辅助 ====================

    public String getUploadPath(Long serviceId, String type) {
        return uploadHelper.getUploadPath(serviceId, type);
    }

    public void extractDist(MultipartFile file, String targetDir) throws IOException {
        uploadHelper.extractDist(file, targetDir);
    }

    public void registerArtifact(Long serviceId, String type, java.io.File savedFile) {
        uploadHelper.registerArtifact(serviceId, type, savedFile);
    }

    public void registerDist(Long serviceId) {
        uploadHelper.registerDist(serviceId);
    }

    // ==================== 部署 ====================

    public Result<Void> deploy(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            return Result.fail("服务不存在: " + serviceId);
        }
        DeployRecord record = newDeployRecord(service);
        String nodeId = remoteDispatcher.resolveTargetNode(service);
        if (nodeId != null) {
            return remoteDispatcher.dispatch(service, nodeId, "START", "running", record);
        }
        return localRunner.runStart(service, record);
    }

    // ==================== 容器操作 ====================

    public Result<Void> stopContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        String nodeId = remoteDispatcher.resolveTargetNode(service);
        if (nodeId != null) {
            return remoteDispatcher.dispatch(service, nodeId, "STOP", "stopped", newDeployRecord(service));
        }
        return localRunner.runStop(service);
    }

    public Result<Void> restartContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        String nodeId = remoteDispatcher.resolveTargetNode(service);
        if (nodeId != null) {
            return remoteDispatcher.dispatch(service, nodeId, "RESTART", "running", newDeployRecord(service));
        }
        return localRunner.runRestart(service);
    }

    public Result<Void> removeContainer(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        String nodeId = remoteDispatcher.resolveTargetNode(service);
        if (nodeId != null) {
            return remoteDispatcher.dispatch(service, nodeId, "REMOVE", "stopped", newDeployRecord(service));
        }
        return localRunner.runRemove(service);
    }

    public Result<ContainerStatusVO> getContainerStatus(Long serviceId) {
        DeployService service = serviceMapper.selectById(serviceId);
        if (service == null) {
            return Result.fail("服务不存在: " + serviceId);
        }
        String nodeId = service.getNodeId();
        // 本机执行
        if (nodeId == null || nodeId.isBlank()) {
            return Result.ok(localRunner.getStatus(service));
        }
        String resolved = nodeId.trim();
        // 自动调度：优先在线节点，无在线节点回退本机
        if ("auto".equalsIgnoreCase(resolved)) {
            Optional<String> leastLoaded = nodeService.selectLeastLoaded();
            if (leastLoaded.isEmpty()) {
                return Result.ok(localRunner.getStatus(service));
            }
            return remoteStatus(service, leastLoaded.get());
        }
        // 指定节点：离线时明确返回 offline，不误报本机状态
        if (!nodeService.isOnline(resolved)) {
            ContainerStatusVO vo = new ContainerStatusVO();
            vo.setRunning(false);
            vo.setStatus("offline");
            return Result.ok(vo);
        }
        return remoteStatus(service, resolved);
    }

    /**
     * 远程状态查询：通过 QueryManager 下发 CONTAINER_STATUS_REQ 并同步等待回执
     */
    private Result<ContainerStatusVO> remoteStatus(DeployService service, String nodeId) {
        ContainerStatusRequest req = ContainerStatusRequest.newBuilder()
                .setServiceId(String.valueOf(service.getId()))
                .setDeployName(service.getDeployName())
                .setVolumeDir(service.getVolumeDir())
                .build();
        Optional<ContainerStatusResponse> resp =
                queryManager.queryContainerStatus(nodeId, req, QueryManager.defaultTimeoutMs());
        if (resp.isEmpty()) {
            ContainerStatusVO vo = new ContainerStatusVO();
            vo.setRunning(false);
            vo.setStatus("unknown");
            return Result.ok(vo);
        }
        ContainerStatusResponse r = resp.get();
        ContainerStatusVO vo = new ContainerStatusVO();
        vo.setRunning(r.getRunning());
        String status = r.getStatus();
        vo.setStatus(status != null && !status.isBlank()
                ? status
                : (r.getRunning() ? "running" : "stopped"));
        return Result.ok(vo);
    }

    private DeployRecord newDeployRecord(DeployService service) {
        DeployRecord record = new DeployRecord();
        record.setServiceId(service.getId());
        record.setLogPath(logHelper.buildLogPath(service));
        record.setCreateTime(LocalDateTime.now());
        return record;
    }
}
