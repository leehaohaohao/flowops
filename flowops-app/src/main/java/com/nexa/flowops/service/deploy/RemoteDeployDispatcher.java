package com.nexa.flowops.service.deploy;

import com.nexa.flowops.common.base.BusinessException;
import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.generate.ConfigGeneratorChain;
import com.nexa.flowops.service.generate.DeployContext;
import com.nexa.flowops.service.node.NodeService;
import com.nexa.flowops.service.node.PendingTask;
import com.nexa.flowops.service.node.RemoteTaskManager;
import com.nexa.protocol.EnvelopeOuterClass.Envelope;
import com.nexa.protocol.Task.TaskRequest;
import com.nexa.protocol.codec.ProtocolCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 远程任务下发：把配置生成链产物打包进 TaskRequest.config，下发给子节点并登记 pending 任务。
 * 执行结果由子节点回执异步落库（见 RemoteTaskManager）。
 */
@Component
public class RemoteDeployDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RemoteDeployDispatcher.class);

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final ObjectMapper objectMapper;
    private final ConfigGeneratorChain configGeneratorChain;
    private final NodeService nodeService;
    private final RemoteTaskManager remoteTaskManager;

    public RemoteDeployDispatcher(DeployServiceMapper serviceMapper,
                                  DeployRecordMapper recordMapper,
                                  ObjectMapper objectMapper,
                                  ConfigGeneratorChain configGeneratorChain,
                                  NodeService nodeService,
                                  RemoteTaskManager remoteTaskManager) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.objectMapper = objectMapper;
        this.configGeneratorChain = configGeneratorChain;
        this.nodeService = nodeService;
        this.remoteTaskManager = remoteTaskManager;
    }

    /**
     * 解析目标执行节点：null=本机；否则返回具体 runnerId
     */
    public String resolveTargetNode(DeployService service) {
        if (service == null) return null;
        String nodeId = service.getNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        if ("auto".equalsIgnoreCase(nodeId.trim())) {
            return nodeService.selectLeastLoaded()
                    .orElseThrow(() -> new BusinessException("无可用子节点，请先接入子节点或改用本机执行"));
        }
        String resolved = nodeId.trim();
        if (!nodeService.isOnline(resolved)) {
            throw new BusinessException("目标节点不在线: " + resolved);
        }
        return resolved;
    }

    /**
     * 打包 TaskRequest 并下发到指定子节点。record 需已设置 logPath/serviceId/createTime。
     */
    public Result<Void> dispatch(DeployService service, String nodeId, String action,
                                 String successStatus, DeployRecord record) {
        try {
            Map<String, String> config = buildConfigMap(service);

            String taskId = UUID.randomUUID().toString();
            TaskRequest req = TaskRequest.newBuilder()
                    .setTaskId(taskId)
                    .setServiceId(String.valueOf(service.getId()))
                    .setDeployName(service.getDeployName())
                    .setAction(action)
                    .setVolumeDir(service.getVolumeDir())
                    .putAllConfig(config)
                    .build();
            Envelope envelope = ProtocolCodec.buildTaskDispatchRequest(nodeId, req);

            log.info("[{}] 远程任务下发: taskId={}, nodeId={}, action={}", service.getName(), taskId, nodeId, action);
            return nodeService.withRunnerLock(nodeId, () -> {
                NodeService.SessionTarget target = nodeService.getCurrentTarget(nodeId).orElse(null);
                if (target == null || !target.send(envelope)) {
                    log.warn("[{}] 远程任务下发失败，目标节点不可写: nodeId={}", service.getName(), nodeId);
                    return failDispatch(record, service, "下发失败：目标节点不在线: " + nodeId);
                }

                record.setNodeId(nodeId);
                record.setStatus("pending");
                record.setRemark("任务已下发至节点: " + nodeId);
                recordMapper.insert(record);

                // 发送与登记使用同一个会话目标；断开清理须等登记完成。
                remoteTaskManager.register(new PendingTask(taskId, service.getId(), nodeId, action,
                        record.getLogPath(), record.getId(), successStatus, target.generation(),
                        System.currentTimeMillis(), RemoteTaskManager.defaultTimeoutMs()));

                return Result.ok("任务已下发至节点: " + nodeId);
            });
        } catch (Exception e) {
            log.error("[{}] 远程任务下发异常: action={}, nodeId={}", service.getName(), action, nodeId, e);
            return failDispatch(record, service, "远程任务下发异常: " + e.getMessage());
        }
    }

    private Result<Void> failDispatch(DeployRecord record, DeployService service, String message) {
        record.setStatus("failed");
        record.setRemark(message);
        try {
            recordMapper.insert(record);
        } catch (Exception ignored) {
        }
        service.setStatus("stopped");
        serviceMapper.updateById(service);
        return Result.fail(message);
    }

    /**
     * 生成配置文件（写入本机 volumeDir）并读取为 config map：相对路径->内容，另附元数据 JSON
     */
    private Map<String, String> buildConfigMap(DeployService service) throws IOException {
        DeployContext context = DeployContext.from(service, objectMapper);
        configGeneratorChain.generate(context);

        Map<String, String> config = new LinkedHashMap<>();
        File volumeDir = new File(service.getVolumeDir());
        putFileIfExists(config, volumeDir, "docker-compose.yml");
        putFileIfExists(config, volumeDir, "Dockerfile");
        putFileIfExists(config, volumeDir, "default.conf");

        if (service.getServiceConfig() != null && !service.getServiceConfig().isEmpty()) {
            config.put("service_config", service.getServiceConfig());
        }
        if (service.getPortMappings() != null && !service.getPortMappings().isEmpty()) {
            config.put("port_mappings", service.getPortMappings());
        }
        config.put("service_type", service.getServiceType());
        return config;
    }

    private void putFileIfExists(Map<String, String> config, File baseDir, String relativePath) {
        File f = new File(baseDir, relativePath);
        if (f.exists() && f.isFile()) {
            try {
                config.put(relativePath, Files.readString(f.toPath()));
            } catch (IOException e) {
                log.warn("读取配置文件失败: {}", relativePath, e);
            }
        }
    }
}
