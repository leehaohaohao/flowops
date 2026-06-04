package com.nexa.flowops.resolver;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.permission.ExternalDataProvider;
import org.springframework.stereotype.Component;

@Component
public class FlowOpsExternalDataProvider implements ExternalDataProvider {

    private final DeployServiceMapper deployServiceMapper;

    public FlowOpsExternalDataProvider(DeployServiceMapper deployServiceMapper) {
        this.deployServiceMapper = deployServiceMapper;
    }

    @Override
    public Long getProjectIdByServiceId(Long serviceId) {
        DeployService service = deployServiceMapper.selectById(serviceId);
        return service != null ? service.getProjectId() : null;
    }

    @Override
    public long countServicesByProjectId(Long projectId) {
        return deployServiceMapper.selectCount(
                new LambdaQueryWrapper<DeployService>().eq(DeployService::getProjectId, projectId));
    }

    @Override
    public long countRunningByProjectId(Long projectId) {
        return deployServiceMapper.selectCount(
                new LambdaQueryWrapper<DeployService>()
                        .eq(DeployService::getProjectId, projectId)
                        .eq(DeployService::getStatus, "running"));
    }
}
