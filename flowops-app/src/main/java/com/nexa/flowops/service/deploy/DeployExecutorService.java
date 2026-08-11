package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.ContainerStatusVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;

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

    public DeployExecutorService(DeployServiceMapper serviceMapper,
                                 UploadHelper uploadHelper,
                                 LocalDeployRunner localRunner,
                                 RemoteDeployDispatcher remoteDispatcher,
                                 DeployLogHelper logHelper) {
        this.serviceMapper = serviceMapper;
        this.uploadHelper = uploadHelper;
        this.localRunner = localRunner;
        this.remoteDispatcher = remoteDispatcher;
        this.logHelper = logHelper;
    }

    // ==================== 上传辅助 ====================

    public String getUploadPath(Long serviceId, String type) {
        return uploadHelper.getUploadPath(serviceId, type);
    }

    public void extractDist(MultipartFile file, String targetDir) throws IOException {
        uploadHelper.extractDist(file, targetDir);
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
        return Result.ok(localRunner.getStatus(service));
    }

    private DeployRecord newDeployRecord(DeployService service) {
        DeployRecord record = new DeployRecord();
        record.setServiceId(service.getId());
        record.setLogPath(logHelper.buildLogPath(service));
        record.setCreateTime(LocalDateTime.now());
        return record;
    }
}
