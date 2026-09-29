package com.nexa.flowops.service.nodepackage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexa.flowops.entity.NexaNodePackage;
import com.nexa.flowops.mapper.NexaNodePackageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布包索引（阶段 2 P3）：按摘要查询、列出、登记。
 *
 * <p>带 {@code -dev} 后缀的 dev 包与对应的 prod 包各自有独立摘要，互不覆盖：
 * 索引与存储都以 {@code sha256} 为唯一键。
 */
@Component
@RequiredArgsConstructor
public class RunnerPackageRegistry {

    private final NexaNodePackageMapper mapper;

    public NexaNodePackage find(String sha256) {
        if (!RunnerPackageValidator.isSha256Hex(sha256)) {
            return null;
        }
        return mapper.selectById(sha256);
    }

    public List<NexaNodePackage> listAll() {
        return mapper.selectList(Wrappers.<NexaNodePackage>lambdaQuery()
                .orderByDesc(NexaNodePackage::getUploadedAt));
    }

    /** 登记一个新包；并发下同摘要已存在时返回既有记录（摘要即身份，内容相同）。 */
    public NexaNodePackage register(RunnerPackageInspection inspection, String uploadedBy) {
        NexaNodePackage entity = new NexaNodePackage();
        entity.setSha256(inspection.sha256());
        entity.setFileName(inspection.fileName());
        entity.setVersion(inspection.version());
        entity.setOs(inspection.os());
        entity.setArch(inspection.arch());
        entity.setFormatVersion(inspection.formatVersion());
        entity.setSizeBytes(inspection.sizeBytes());
        entity.setGitCommit(inspection.gitCommit());
        entity.setImageReference(inspection.imageReference());
        entity.setImageId(inspection.imageId());
        entity.setDockerCliVersion(inspection.dockerCliVersion());
        entity.setComposeVersion(inspection.composeVersion());
        entity.setUploadedBy(uploadedBy);
        entity.setUploadedAt(LocalDateTime.now());
        try {
            mapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException e) {
            NexaNodePackage existing = mapper.selectById(inspection.sha256());
            return existing != null ? existing : entity;
        }
    }
}
