package com.nexa.flowops.service.artifact;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nexa.flowops.entity.DeployArtifact;
import com.nexa.flowops.mapper.DeployArtifactMapper;
import org.springframework.stereotype.Component;

/**
 * 产物注册表：按 (serviceId, type) 查询最新/指定版本、上传登记（version+1）、服务删除级联清理
 */
@Component
public class ArtifactRegistry {

    private final DeployArtifactMapper artifactMapper;

    public ArtifactRegistry(DeployArtifactMapper artifactMapper) {
        this.artifactMapper = artifactMapper;
    }

    /** 查询某服务某类型的最新产物；无记录返回 null */
    public DeployArtifact findLatest(Long serviceId, String type) {
        return artifactMapper.selectOne(new LambdaQueryWrapper<DeployArtifact>()
                .eq(DeployArtifact::getServiceId, serviceId)
                .eq(DeployArtifact::getType, type)
                .orderByDesc(DeployArtifact::getVersion)
                .last("LIMIT 1"));
    }

    /** 按版本查询；version 必须 > 0 */
    public DeployArtifact findByVersion(Long serviceId, String type, int version) {
        return artifactMapper.selectOne(new LambdaQueryWrapper<DeployArtifact>()
                .eq(DeployArtifact::getServiceId, serviceId)
                .eq(DeployArtifact::getType, type)
                .eq(DeployArtifact::getVersion, version));
    }

    /** 登记一次上传：同服务同类型 version+1 */
    public DeployArtifact register(Long serviceId, String deployName, String type,
                                   String fileName, String storagePath, long size, String checksum) {
        Integer maxVersion = artifactMapper.selectObjs(new QueryWrapper<DeployArtifact>()
                        .select("MAX(version)")
                        .eq("service_id", serviceId)
                        .eq("type", type))
                .stream()
                .filter(o -> o != null)
                .map(o -> ((Number) o).intValue())
                .findFirst()
                .orElse(0);

        DeployArtifact artifact = new DeployArtifact();
        artifact.setServiceId(serviceId);
        artifact.setDeployName(deployName);
        artifact.setType(type);
        artifact.setFileName(fileName);
        artifact.setStoragePath(storagePath);
        artifact.setSize(size);
        artifact.setChecksum(checksum);
        artifact.setVersion(maxVersion + 1);
        artifactMapper.insert(artifact);
        return artifact;
    }

    /** 服务删除时级联清理注册表 */
    public void deleteByService(Long serviceId) {
        artifactMapper.delete(new LambdaQueryWrapper<DeployArtifact>()
                .eq(DeployArtifact::getServiceId, serviceId));
    }
}
