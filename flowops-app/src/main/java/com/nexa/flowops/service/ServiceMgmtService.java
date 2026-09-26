package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.base.BusinessException;
import com.nexa.flowops.dto.CreateServiceRequest;
import com.nexa.flowops.dto.UpdateServiceRequest;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.service.artifact.ArtifactRegistry;
import com.nexa.flowops.service.network.DockerNetworkService;
import com.nexa.flowops.service.network.NetworkAuthorizationService;
import com.nexa.flowops.service.network.NetworkException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ServiceMgmtService {

    private static final Pattern DEPLOY_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$");

    private final DeployServiceMapper serviceMapper;
    private final ArtifactRegistry artifactRegistry;
    private final DockerNetworkService networkService;
    private final NetworkAuthorizationService networkAuthorizationService;
    private final String storagePath = "/data/flowops/services";

    public ServiceMgmtService(DeployServiceMapper serviceMapper,
                              ArtifactRegistry artifactRegistry,
                              DockerNetworkService networkService,
                              NetworkAuthorizationService networkAuthorizationService) {
        this.serviceMapper = serviceMapper;
        this.artifactRegistry = artifactRegistry;
        this.networkService = networkService;
        this.networkAuthorizationService = networkAuthorizationService;
    }

    public List<DeployService> list() {
        return serviceMapper.selectList(null);
    }

    public List<DeployService> listByProjectIds(List<Long> projectIds) {
        if (projectIds.isEmpty()) return List.of();
        return serviceMapper.selectList(
                new LambdaQueryWrapper<DeployService>().in(DeployService::getProjectId, projectIds));
    }

    public DeployService getById(Long id) {
        return serviceMapper.selectById(id);
    }

    public void createService(CreateServiceRequest req) {
        validateDeployName(req.getDeployName());

        Long count = serviceMapper.selectCount(
                new LambdaQueryWrapper<DeployService>().eq(DeployService::getDeployName, req.getDeployName()));
        if (count > 0) {
            throw new BusinessException("部署名称「" + req.getDeployName() + "」已存在，请更换");
        }

        DeployService service = new DeployService();
        service.setName(req.getName());
        service.setDeployName(req.getDeployName());
        service.setRemark(req.getRemark());
        service.setPortMappings(req.getPortMappings());
        service.setServiceType(req.getServiceType());
        service.setServiceConfig(req.getServiceConfig());
        service.setProjectId(req.getProjectId());
        service.setNodeId(req.getNodeId());
        validateNetworkSelection(req.getProjectId(), req.getNodeId(), req.getNetworkId());
        service.setNetworkId(req.getNetworkId());
        service.setVolumeDir(storagePath + "/" + req.getDeployName());
        service.setStatus("stopped");
        serviceMapper.insert(service);

        new File(service.getVolumeDir()).mkdirs();
    }

    public void updateService(Long id, UpdateServiceRequest req) {
        DeployService service = serviceMapper.selectById(id);
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        if (req.getName() != null) {
            service.setName(req.getName());
        }
        if (req.getDeployName() != null) {
            validateDeployName(req.getDeployName());
            if (!req.getDeployName().equals(service.getDeployName())) {
                Long count = serviceMapper.selectCount(
                        new LambdaQueryWrapper<DeployService>().eq(DeployService::getDeployName, req.getDeployName()));
                if (count > 0) {
                    throw new BusinessException("部署名称「" + req.getDeployName() + "」已存在，请更换");
                }
                // rename 物理目录
                File oldDir = new File(service.getVolumeDir());
                String newVolumeDir = storagePath + "/" + req.getDeployName();
                if (oldDir.exists()) {
                    if (!oldDir.renameTo(new File(newVolumeDir))) {
                        throw new BusinessException("重命名目录失败: " + service.getVolumeDir());
                    }
                }
                service.setDeployName(req.getDeployName());
                service.setVolumeDir(newVolumeDir);
            }
        }
        if (req.getRemark() != null) {
            service.setRemark(req.getRemark());
        }
        if (req.getPortMappings() != null) {
            service.setPortMappings(req.getPortMappings());
        }
        if (req.getServiceType() != null) {
            service.setServiceType(req.getServiceType());
        }
        if (req.getServiceConfig() != null) {
            service.setServiceConfig(req.getServiceConfig());
        }
        if (req.getNodeId() != null) {
            service.setNodeId(req.getNodeId());
        }
        // 接口契约：networkId 为 number|null，null 即表示不加入共享网络（此处按提交值生效）
        validateNetworkSelection(service.getProjectId(), service.getNodeId(), req.getNetworkId());
        service.setNetworkId(req.getNetworkId());
        serviceMapper.updateById(service);
    }

    /**
     * 共享网络选择校验（B4）：选网络时目标节点必须是本机，网络必须已登记且已授权给该服务所属项目。
     * 未选网络（null）时不做任何限制，旧服务行为不变。
     */
    private void validateNetworkSelection(Long projectId, String nodeId, Long networkId) {
        if (networkId == null) {
            return;
        }
        if (nodeId != null && !nodeId.isBlank()) {
            throw NetworkException.invalid("选择共享网络时目标节点必须是本机，runner/auto 与共享网络互斥");
        }
        if (projectId == null) {
            throw NetworkException.invalid("服务缺少所属项目，无法校验网络授权");
        }
        networkService.requireRegistered(networkId);
        if (!networkAuthorizationService.isGranted(projectId, networkId)) {
            throw NetworkException.forbidden("该项目未获授权使用该网络");
        }
    }

    private void validateDeployName(String deployName) {
        if (deployName == null || deployName.isBlank()) {
            throw new BusinessException("部署名称不能为空");
        }
        if (!DEPLOY_NAME_PATTERN.matcher(deployName).matches()) {
            throw new BusinessException("部署名称只能包含小写字母、数字和连字符，且首尾为字母或数字，长度 2-63");
        }
    }

    public void deleteService(Long id) {
        // 级联清理产物注册表（存储文件随 volumeDir 清理，逻辑不变）
        artifactRegistry.deleteByService(id);
        serviceMapper.deleteById(id);
    }
}