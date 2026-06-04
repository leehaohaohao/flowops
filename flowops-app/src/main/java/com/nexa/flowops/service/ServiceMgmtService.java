package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.base.BusinessException;
import com.nexa.flowops.dto.CreateServiceRequest;
import com.nexa.flowops.dto.UpdateServiceRequest;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ServiceMgmtService {

    private static final Pattern DEPLOY_NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$");

    private final DeployServiceMapper serviceMapper;
    private final String storagePath = "/data/flowops/services";

    public ServiceMgmtService(DeployServiceMapper serviceMapper) {
        this.serviceMapper = serviceMapper;
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
        serviceMapper.updateById(service);
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
        serviceMapper.deleteById(id);
    }
}
