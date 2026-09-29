package com.nexa.flowops.service.nodepackage;

import com.nexa.flowops.dto.RunnerPackageVO;
import com.nexa.flowops.entity.NexaNodePackage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 发布包上传与查询（阶段 2 P3，契约 {@code docs/frontend-api/node-package-api.md} §2–§3）。
 *
 * <p>上传流程：文件名预检 → 大小预检 → 单遍流式校验（写入临时文件）→ 相同摘要短路 →
 * 原子改名入不可变存储 → 登记索引。**不解压落盘、不执行包内任何内容**；
 * 包内不含 runnerId、主节点地址、token 等节点凭据，本服务也不接受任何凭据作为上传输入。
 */
@Service
@RequiredArgsConstructor
public class RunnerPackageService {

    private static final Logger log = LoggerFactory.getLogger(RunnerPackageService.class);

    private final RunnerPackageValidator validator;
    private final LocalRunnerPackageStore store;
    private final RunnerPackageRegistry registry;
    private final RunnerPackageSettings settings;

    /**
     * 上传结果（R2 契约的五种分支）。
     *
     * @param existing  索引行此前已存在
     * @param repaired  本次请求**重写了主节点磁盘上的包文件**（文件缺失/损坏时按摘要修复）
     * @param message   该分支的固定成功文案
     */
    public record UploadOutcome(RunnerPackageVO packageInfo, boolean existing, boolean repaired, String message) {
    }

    public UploadOutcome upload(MultipartFile file, String operator) {
        if (file == null || file.isEmpty()) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_EMPTY);
        }
        String fileName = file.getOriginalFilename();
        // 廉价预检先返回，避免为一个明显不合规的文件白传 1 GiB
        validator.requireValidFileName(fileName);
        if (file.getSize() > settings.getMaxSize().toBytes()) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_TOO_LARGE);
        }

        Path tempFile = store.createTempFile();
        RunnerPackageInspection inspection;
        try {
            try (InputStream in = file.getInputStream()) {
                inspection = validator.validate(in, fileName, tempFile);
            }
        } catch (RunnerPackageException e) {
            store.deleteQuietly(tempFile);
            throw e;
        } catch (IOException e) {
            log.warn("[RunnerPackage] 读取上传流失败: {}", e.getClass().getSimpleName());
            store.deleteQuietly(tempFile);
            throw new RunnerPackageException(RunnerPackageFailure.STORE_UNAVAILABLE);
        }

        // R2：索引与卷文件可能失同步。摘要即身份，因此"内容是否等于摘要"是唯一判据；
        // 健康文件永不被覆盖，缺失/损坏文件用本次已校验的上传内容原子修复。
        String sha256 = inspection.sha256();
        NexaNodePackage existing = registry.find(sha256);
        if (existing != null) {
            if (store.isHealthy(sha256, existing.getSizeBytes())) {
                store.deleteQuietly(tempFile);
                return new UploadOutcome(toVO(existing, true, false), true, false, "发布包已存在（相同摘要）");
            }
            log.warn("[RunnerPackage] 索引与存储文件失同步，按摘要修复: sha256={}", sha256);
            replaceOrFail(tempFile, sha256);
            return new UploadOutcome(toVO(existing, true, true), true, true,
                    "发布包已存在，已按摘要修复存储文件");
        }

        boolean repaired = false;
        if (store.exists(sha256)) {
            if (store.isHealthy(sha256, inspection.sizeBytes())) {
                // 文件曾落盘但索引登记失败（或索引行被删）：复核内容后补登记，不重写文件
                store.deleteQuietly(tempFile);
                NexaNodePackage registered = registry.register(inspection, operator);
                log.info("[RunnerPackage] 发布包已补登记: sha256={} version={}", sha256, inspection.version());
                return new UploadOutcome(toVO(registered, false, false), false, false,
                        "发布包已补登记（文件已存在且摘要一致）");
            }
            log.warn("[RunnerPackage] 无索引但同名文件内容不符，按摘要替换: sha256={}", sha256);
            replaceOrFail(tempFile, sha256);
            repaired = true;
        } else {
            // 全新路径保留"已存在则不动"的语义：并发同摘要上传时不会覆盖对方（内容必然相同）
            commitOrFail(tempFile, sha256);
        }
        NexaNodePackage saved = registry.register(inspection, operator);
        log.info("[RunnerPackage] 发布包已入库: sha256={} version={} size={}B file={} repaired={}",
                sha256, inspection.version(), inspection.sizeBytes(), inspection.fileName(), repaired);
        return new UploadOutcome(toVO(saved, false, repaired), false, repaired, "发布包已上传");
    }

    /** 失败路径统一清理临时文件后再抛出。 */
    private void commitOrFail(Path tempFile, String sha256) {
        try {
            store.commit(tempFile, sha256);
        } catch (RunnerPackageException e) {
            store.deleteQuietly(tempFile);
            throw e;
        }
    }

    /** 修复路径：目标缺失或内容不符时原子替换（健康文件不走这里）。 */
    private void replaceOrFail(Path tempFile, String sha256) {
        try {
            store.commitReplacing(tempFile, sha256);
        } catch (RunnerPackageException e) {
            store.deleteQuietly(tempFile);
            throw e;
        }
    }

    public List<RunnerPackageVO> list() {
        return registry.listAll().stream().map(entity -> toVO(entity, null, null)).toList();
    }

    public RunnerPackageVO get(String sha256) {
        if (!RunnerPackageValidator.isSha256Hex(sha256)) {
            throw new RunnerPackageException(RunnerPackageFailure.SHA256_INVALID);
        }
        NexaNodePackage entity = registry.find(sha256);
        if (entity == null) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NOT_FOUND, sha256);
        }
        return toVO(entity, null, null);
    }

    /** 已存储包的文件路径（供分发读取）；不存在按 404 处理。 */
    public Path storedPackage(String sha256) {
        return store.storedPackage(sha256);
    }

    public NexaNodePackage requireEntity(String sha256) {
        if (!RunnerPackageValidator.isSha256Hex(sha256)) {
            throw new RunnerPackageException(RunnerPackageFailure.SHA256_INVALID);
        }
        NexaNodePackage entity = registry.find(sha256);
        if (entity == null) {
            throw new RunnerPackageException(RunnerPackageFailure.PACKAGE_NOT_FOUND, sha256);
        }
        return entity;
    }

    static RunnerPackageVO toVO(NexaNodePackage entity, Boolean existing, Boolean repaired) {
        RunnerPackageVO vo = new RunnerPackageVO();
        vo.setSha256(entity.getSha256());
        vo.setFileName(entity.getFileName());
        vo.setVersion(entity.getVersion());
        vo.setOs(entity.getOs());
        vo.setArch(entity.getArch());
        vo.setSizeBytes(entity.getSizeBytes());
        vo.setGitCommit(entity.getGitCommit());
        vo.setImageReference(entity.getImageReference());
        vo.setImageId(entity.getImageId());
        vo.setDockerCliVersion(entity.getDockerCliVersion());
        vo.setComposeVersion(entity.getComposeVersion());
        vo.setFormatVersion(entity.getFormatVersion());
        vo.setUploadedBy(entity.getUploadedBy());
        vo.setUploadedAt(entity.getUploadedAt());
        vo.setExisting(existing);
        vo.setRepaired(repaired);
        return vo;
    }
}
