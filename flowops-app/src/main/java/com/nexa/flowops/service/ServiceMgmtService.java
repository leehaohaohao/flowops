package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.dto.CreateServiceRequest;
import com.nexa.flowops.dto.UpdateServiceRequest;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
public class ServiceMgmtService {

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
        Long count = serviceMapper.selectCount(
                new LambdaQueryWrapper<DeployService>().eq(DeployService::getName, req.getName()));
        if (count > 0) {
            throw new BusinessException("服务名「" + req.getName() + "」已存在，请更换名称");
        }

        DeployService service = new DeployService();
        service.setName(req.getName());
        service.setPort(req.getPort());
        service.setExtraPorts(req.getExtraPorts());
        service.setServiceType(req.getServiceType());
        service.setServiceConfig(req.getServiceConfig());
        service.setProjectId(req.getProjectId());
        service.setVolumeDir(storagePath + "/" + req.getName());
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
        if (req.getPort() != null) {
            service.setPort(req.getPort());
        }
        if (req.getExtraPorts() != null) {
            service.setExtraPorts(req.getExtraPorts());
        }
        if (req.getServiceType() != null) {
            service.setServiceType(req.getServiceType());
        }
        if (req.getServiceConfig() != null) {
            service.setServiceConfig(req.getServiceConfig());
        }
        serviceMapper.updateById(service);
    }

    public void deleteService(Long id) {
        serviceMapper.deleteById(id);
    }
}
