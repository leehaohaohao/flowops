package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.DockerNetwork;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.network.DockerNetworkService;
import com.nexa.flowops.service.network.NetworkAuthorizationService;
import com.nexa.flowops.service.network.NetworkException;
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
    private final DockerNetworkService networkService;
    private final NetworkAuthorizationService networkAuthorizationService;

    public DeployExecutorService(DeployServiceMapper serviceMapper,
                                 UploadHelper uploadHelper,
                                 LocalDeployRunner localRunner,
                                 RemoteDeployDispatcher remoteDispatcher,
                                 DeployLogHelper logHelper,
                                 NodeService nodeService,
                                 QueryManager queryManager,
                                 DockerNetworkService networkService,
                                 NetworkAuthorizationService networkAuthorizationService) {
        this.serviceMapper = serviceMapper;
        this.uploadHelper = uploadHelper;
        this.localRunner = localRunner;
        this.remoteDispatcher = remoteDispatcher;
        this.logHelper = logHelper;
        this.nodeService = nodeService;
        this.queryManager = queryManager;
        this.networkService = networkService;
        this.networkAuthorizationService = networkAuthorizationService;
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
        Result<Void> networkCheck = validateNetworkForDeploy(service);
        if (networkCheck != null) {
            return networkCheck;
        }
        DeployRecord record = newDeployRecord(service);
        String nodeId = remoteDispatcher.resolveTargetNode(service);
        if (nodeId != null) {
            return remoteDispatcher.dispatch(service, nodeId, "START", "running", record);
        }
        return localRunner.runStart(service, record);
    }

    /**
     * 部署前共享网络校验（B4）：选网络的服务必须本机执行，网络已登记且在主节点 Docker 中存在，
     * 且项目仍获授权。未选网络的服务不受影响，调度行为不变。
     */
    private Result<Void> validateNetworkForDeploy(DeployService service) {
        if (service.getNetworkId() == null) {
            return null;
        }
        try {
            String nodeId = service.getNodeId();
            if (nodeId != null && !nodeId.isBlank()) {
                throw NetworkException.invalid("选择共享网络时目标节点必须是本机，runner/auto 与共享网络互斥");
            }
            DockerNetwork network = networkService.requireRegistered(service.getNetworkId());
            if (!networkService.inspect(network).present()) {
                throw NetworkException.dockerMissing("共享网络在主节点 Docker 中不存在: " + network.getName());
            }
            if (!networkAuthorizationService.isGranted(service.getProjectId(), service.getNetworkId())) {
                throw NetworkException.forbidden("该项目未获授权使用该网络");
            }
            return null;
        } catch (NetworkException e) {
            log.warn("[{}] 部署前网络校验未通过: {}", service.getName(), e.getMessage());
            return Result.fail(e.getCode(), e.getMessage());
        }
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
