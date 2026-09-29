package com.nexa.flowops.service.nodepackage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexa.flowops.dto.PackageDistributionVO;
import com.nexa.flowops.entity.NexaNodePackage;
import com.nexa.flowops.entity.NexaNodePackageDistribution;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.mapper.NexaNodePackageDistributionMapper;
import com.nexa.flowops.mapper.NexaNodePackageMapper;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import com.nexa.flowops.service.node.SshTestResultCode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 发布包分发编排（阶段 2 P3，契约 {@code docs/frontend-api/node-package-api.md} §4–§5）。
 *
 * <p>职责：
 * <ul>
 *   <li>请求级前置校验：节点已登记、包已存储、SSH 目标存在且算法已补齐、阶段 1 最近一次测试为 {@code CONNECTED}；</li>
 *   <li>幂等与并发：同摘要进行中返回既有记录，同节点其它摘要在途返回 409，全局队列满返回 409，同一节点始终串行；</li>
 *   <li>异步执行：建记录（{@code PENDING}）后交给有界线程池，运行期结果写 {@code SUCCEEDED/FAILED} + 固定错误码；</li>
 *   <li>重启恢复：主节点重启后把在途记录标记为 {@code FAILED(MASTER_RESTARTED)} 并清理上传临时文件。</li>
 * </ul>
 *
 * <p>分发只投递不可变通用包：不写节点凭据、不解压、不启动子节点、不轮换注册 token。
 */
@Service
@RequiredArgsConstructor
public class RunnerPackageDistributionService {

    private static final Logger log = LoggerFactory.getLogger(RunnerPackageDistributionService.class);

    private static final List<String> IN_FLIGHT_STATUSES = List.of(
            PackageDistributionStatus.PENDING.name(),
            PackageDistributionStatus.UPLOADING.name(),
            PackageDistributionStatus.VERIFYING.name());

    private final NexaNodeMapper nodeMapper;
    private final NexaNodeSshTargetMapper targetMapper;
    private final NexaNodePackageMapper packageMapper;
    private final NexaNodePackageDistributionMapper distributionMapper;
    private final RunnerPackageService packageService;
    private final RunnerPackageDistributor distributor;
    private final RunnerPackageSettings settings;
    private final LocalRunnerPackageStore store;

    private volatile ThreadPoolExecutor executor;
    /** 每个节点一把进程内锁，供"在途判定 + 建记录 + 入队"的原子化使用 */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> nodeLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ==================== 请求入口 ====================

    /** 触发一次分发（异步）：立即返回记录，前端轮询单条记录。 */
    public PackageDistributionVO distribute(String runnerId, String sha256, String operator) {
        requireRegistered(runnerId);
        NexaNodePackage pkg = packageService.requireEntity(sha256);
        NexaNodeSshTarget target = requireVerifiedTarget(runnerId);

        // 同一节点的"在途判定 + 建记录 + 入队"必须原子：否则两个并发请求（双击/两个标签页）
        // 可能都通过在途检查，产生两条记录并开两条 SSH 会话。锁只覆盖这段临界区，
        // 真正的传输由记录状态串行化，不占锁。
        NexaNodePackageDistribution record;
        synchronized (nodeLock(runnerId)) {
            // 判定顺序：同摘要进行中 → 该节点其它摘要在途 → 队列已满 → 新建
            List<NexaNodePackageDistribution> inFlight = findInFlight(runnerId);
            for (NexaNodePackageDistribution existing : inFlight) {
                if (existing.getPackageSha256().equals(sha256)) {
                    return toVO(existing, pkg);
                }
            }
            if (!inFlight.isEmpty()) {
                throw PackageDistributionRequestException.inProgress();
            }
            if (isSaturated()) {
                throw PackageDistributionRequestException.queueFull();
            }

            record = new NexaNodePackageDistribution();
            record.setRunnerId(runnerId);
            record.setPackageSha256(sha256);
            // R1：绑定"此刻已验证通过的那一版 SSH 设置"（阶段 1 config_version）。
            // 后台任务必须证明当前版本仍等于它，否则不得建立远端会话。
            record.setSshConfigVersion(target.getConfigVersion());
            record.setStatus(PackageDistributionStatus.PENDING.name());
            record.setAlreadyPresent(false);
            record.setRemotePath(distributor.finalRemotePath(sha256));
            record.setSizeBytes(pkg.getSizeBytes());
            record.setOperator(operator);
            record.setStartedAt(LocalDateTime.now());
            distributionMapper.insert(record);

            try {
                Long recordId = record.getId();
                executor().execute(() -> runDistribution(recordId));
            } catch (RejectedExecutionException e) {
                distributionMapper.deleteById(record.getId());
                throw PackageDistributionRequestException.queueFull();
            }
        }
        log.info("[RunnerPackage] 分发已排队 runnerId={} sha256={} recordId={} 目标={}",
                runnerId, sha256, record.getId(), target.getHost());
        return toVO(record, pkg);
    }

    /** 每个节点一把进程内锁：并发请求下保证同节点串行分发的判定与建记录是原子的。 */
    private Object nodeLock(String runnerId) {
        return nodeLocks.computeIfAbsent(runnerId, key -> new Object());
    }

    /** 该节点每个包的最新一次分发记录（按记录 id 倒序）。 */
    public List<PackageDistributionVO> listForNode(String runnerId) {
        requireRegistered(runnerId);
        List<NexaNodePackageDistribution> records = distributionMapper.selectList(
                Wrappers.<NexaNodePackageDistribution>lambdaQuery()
                        .eq(NexaNodePackageDistribution::getRunnerId, runnerId)
                        .orderByDesc(NexaNodePackageDistribution::getId));
        Map<String, NexaNodePackage> packages = loadPackages(records);
        List<PackageDistributionVO> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (NexaNodePackageDistribution record : records) {
            if (seen.add(record.getPackageSha256())) {
                result.add(toVO(record, packages.get(record.getPackageSha256())));
            }
        }
        return result;
    }

    /** 单条记录（轮询用）；记录不存在或不属于该节点按 404 处理。 */
    public PackageDistributionVO getRecord(String runnerId, Long id) {
        NexaNodePackageDistribution record = id == null ? null : distributionMapper.selectById(id);
        if (record == null || !record.getRunnerId().equals(runnerId)) {
            throw PackageDistributionRequestException.recordNotFound(id);
        }
        return toVO(record, packageMapper.selectById(record.getPackageSha256()));
    }

    // ==================== 异步执行 ====================

    private void runDistribution(Long recordId) {
        NexaNodePackageDistribution record = distributionMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        NexaNodePackage pkg = packageMapper.selectById(record.getPackageSha256());
        if (pkg == null) {
            finishFailed(recordId, PackageDistributionErrorCode.INTERNAL_ERROR, startedAt);
            return;
        }

        // R1：排队到执行之间 SSH 设置可能被改过。这里**只读一次**目标，并在建立任何远端会话之前证明：
        // ① 记录绑定过版本；② 当前 config_version 仍等于绑定版本；③ 最近一次测试仍是 CONNECTED。
        // 通过后就用这同一个对象建会话，不再二次读取——传输期间即使管理员改了设置，本次也只作用于已核对的那一版。
        NexaNodeSshTarget target = targetMapper.selectById(record.getRunnerId());
        PackageDistributionErrorCode bindingFailure = checkSshBinding(record, target);
        if (bindingFailure != null) {
            log.warn("[RunnerPackage] 分发前置失效 runnerId={} sha256={} 绑定版本={} 当前版本={} errorCode={}",
                    record.getRunnerId(), record.getPackageSha256(), record.getSshConfigVersion(),
                    target == null ? null : target.getConfigVersion(), bindingFailure.name());
            finishFailed(recordId, bindingFailure, startedAt);
            return;
        }

        try {
            Path packageFile = store.packagePath(record.getPackageSha256());
            RunnerPackageDistributor.Outcome outcome = distributor.distribute(
                    record.getRunnerId(), record.getPackageSha256(), packageFile, pkg.getSizeBytes(), target,
                    stage -> updateStage(recordId, stage));
            finishSucceeded(recordId, outcome, startedAt);
        } catch (PackageDistributionFailure e) {
            log.warn("[RunnerPackage] 分发失败 runnerId={} sha256={} errorCode={}",
                    record.getRunnerId(), record.getPackageSha256(), e.errorCode());
            finishFailed(recordId, e, startedAt);
        } catch (Exception e) {
            log.warn("[RunnerPackage] 分发出现未预期错误 runnerId={} sha256={} 原因={}",
                    record.getRunnerId(), record.getPackageSha256(), e.getClass().getSimpleName());
            finishFailed(recordId, PackageDistributionErrorCode.INTERNAL_ERROR, startedAt);
        }
    }

    /**
     * 分发前的 SSH 绑定核对（R1）：返回 {@code null} 表示可以建立会话，否则返回固定失败码。
     *
     * <p>三种失效都发生在建会话之前，因此目标机不会出现任何新文件或残留临时文件。
     */
    private PackageDistributionErrorCode checkSshBinding(NexaNodePackageDistribution record, NexaNodeSshTarget current) {
        Long bound = record.getSshConfigVersion();
        if (bound == null) {
            // V3_1_9 之前的历史记录没有绑定过版本：不能假定它对应哪一版设置
            return PackageDistributionErrorCode.SSH_CONFIG_CHANGED;
        }
        if (current == null) {
            // 目标被删除，或节点 SSH 设置被清空
            return PackageDistributionErrorCode.SSH_CONFIG_CHANGED;
        }
        if (!bound.equals(current.getConfigVersion())) {
            return PackageDistributionErrorCode.SSH_CONFIG_CHANGED;
        }
        if (!SshTestResultCode.CONNECTED.name().equals(current.getLastResultCode())) {
            // 版本没变但最近一次测试不是 CONNECTED（例如重测失败）：同样不得分发
            return PackageDistributionErrorCode.SSH_NOT_VERIFIED;
        }
        return null;
    }

    private void updateStage(Long recordId, PackageDistributionStatus status) {
        distributionMapper.update(null, Wrappers.<NexaNodePackageDistribution>lambdaUpdate()
                .eq(NexaNodePackageDistribution::getId, recordId)
                .set(NexaNodePackageDistribution::getStatus, status.name()));
    }

    private void finishSucceeded(Long recordId, RunnerPackageDistributor.Outcome outcome, long startedAt) {
        distributionMapper.update(null, Wrappers.<NexaNodePackageDistribution>lambdaUpdate()
                .eq(NexaNodePackageDistribution::getId, recordId)
                .set(NexaNodePackageDistribution::getStatus, PackageDistributionStatus.SUCCEEDED.name())
                .set(NexaNodePackageDistribution::getAlreadyPresent, outcome.alreadyPresent())
                .set(NexaNodePackageDistribution::getRemotePath, outcome.remotePath())
                .set(NexaNodePackageDistribution::getErrorCode, null)
                .set(NexaNodePackageDistribution::getErrorMessage, null)
                .set(NexaNodePackageDistribution::getFinishedAt, LocalDateTime.now())
                .set(NexaNodePackageDistribution::getDurationMs, System.currentTimeMillis() - startedAt));
    }

    private void finishFailed(Long recordId, PackageDistributionErrorCode code, long startedAt) {
        finishFailed(recordId, code.name(), code.message(), startedAt);
    }

    private void finishFailed(Long recordId, PackageDistributionFailure failure, long startedAt) {
        finishFailed(recordId, failure.errorCode(), failure.errorMessage(), startedAt);
    }

    private void finishFailed(Long recordId, String errorCode, String errorMessage, long startedAt) {
        distributionMapper.update(null, Wrappers.<NexaNodePackageDistribution>lambdaUpdate()
                .eq(NexaNodePackageDistribution::getId, recordId)
                .set(NexaNodePackageDistribution::getStatus, PackageDistributionStatus.FAILED.name())
                .set(NexaNodePackageDistribution::getErrorCode, errorCode)
                .set(NexaNodePackageDistribution::getErrorMessage, errorMessage)
                .set(NexaNodePackageDistribution::getFinishedAt, LocalDateTime.now())
                .set(NexaNodePackageDistribution::getDurationMs, System.currentTimeMillis() - startedAt));
    }

    // ==================== 重启恢复 ====================

    /**
     * 主节点重启后修复状态：在途记录统一 {@code FAILED(MASTER_RESTARTED)}，并清理上传临时文件。
     *
     * <p>失败只记日志，不影响应用启动（缺库/无权限的环境不应因此起不来）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        try {
            int removed = store.cleanupTempFiles();
            if (removed > 0) {
                log.info("[RunnerPackage] 已清理 {} 个上传临时文件", removed);
            }
            int repaired = distributionMapper.update(null, Wrappers.<NexaNodePackageDistribution>lambdaUpdate()
                    .in(NexaNodePackageDistribution::getStatus, IN_FLIGHT_STATUSES)
                    .set(NexaNodePackageDistribution::getStatus, PackageDistributionStatus.FAILED.name())
                    .set(NexaNodePackageDistribution::getErrorCode,
                            PackageDistributionErrorCode.MASTER_RESTARTED.name())
                    .set(NexaNodePackageDistribution::getErrorMessage,
                            PackageDistributionErrorCode.MASTER_RESTARTED.message())
                    .set(NexaNodePackageDistribution::getFinishedAt, LocalDateTime.now()));
            if (repaired > 0) {
                log.warn("[RunnerPackage] 主节点重启：{} 条未完成分发记录标记为 FAILED(MASTER_RESTARTED)", repaired);
            }
        } catch (Exception e) {
            log.warn("[RunnerPackage] 重启恢复未完成（忽略）: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        ThreadPoolExecutor current = executor;
        if (current != null) {
            current.shutdownNow();
        }
    }

    // ==================== 内部工具 ====================

    private void requireRegistered(String runnerId) {
        if (runnerId == null || runnerId.isBlank() || nodeMapper.selectById(runnerId) == null) {
            throw PackageDistributionRequestException.nodeNotRegistered(runnerId);
        }
    }

    /** 分发前置：SSH 目标必须存在、算法已补齐，且阶段 1 最近一次测试为 CONNECTED。 */
    private NexaNodeSshTarget requireVerifiedTarget(String runnerId) {
        NexaNodeSshTarget target = targetMapper.selectById(runnerId);
        if (target == null) {
            throw PackageDistributionRequestException.sshNotConfigured();
        }
        if (target.getHostKeyAlgorithm() == null || target.getHostKeyAlgorithm().isBlank()) {
            throw PackageDistributionRequestException.algorithmRequired();
        }
        if (!SshTestResultCode.CONNECTED.name().equals(target.getLastResultCode())) {
            throw PackageDistributionRequestException.sshNotVerified();
        }
        return target;
    }

    private List<NexaNodePackageDistribution> findInFlight(String runnerId) {
        return distributionMapper.selectList(Wrappers.<NexaNodePackageDistribution>lambdaQuery()
                .eq(NexaNodePackageDistribution::getRunnerId, runnerId)
                .in(NexaNodePackageDistribution::getStatus, IN_FLIGHT_STATUSES));
    }

    private boolean isSaturated() {
        ThreadPoolExecutor current = executor();
        return current.getActiveCount() >= current.getMaximumPoolSize()
                && current.getQueue().remainingCapacity() == 0;
    }

    private ThreadPoolExecutor executor() {
        ThreadPoolExecutor current = executor;
        if (current == null) {
            synchronized (this) {
                current = executor;
                if (current == null) {
                    int max = Math.max(1, settings.getMaxConcurrentDistributions());
                    current = new ThreadPoolExecutor(max, max, 0L, TimeUnit.MILLISECONDS,
                            new LinkedBlockingQueue<>(Math.max(1, settings.getDistributionQueueCapacity())),
                            runnable -> {
                                Thread thread = new Thread(runnable, "runner-package-distribute");
                                thread.setDaemon(true);
                                return thread;
                            });
                    executor = current;
                }
            }
        }
        return current;
    }

    private Map<String, NexaNodePackage> loadPackages(List<NexaNodePackageDistribution> records) {
        Set<String> shas = new HashSet<>();
        for (NexaNodePackageDistribution record : records) {
            shas.add(record.getPackageSha256());
        }
        Map<String, NexaNodePackage> packages = new HashMap<>();
        if (!shas.isEmpty()) {
            for (NexaNodePackage pkg : packageMapper.selectBatchIds(shas)) {
                packages.put(pkg.getSha256(), pkg);
            }
        }
        return packages;
    }

    static PackageDistributionVO toVO(NexaNodePackageDistribution record, NexaNodePackage pkg) {
        PackageDistributionVO vo = new PackageDistributionVO();
        vo.setId(record.getId());
        vo.setRunnerId(record.getRunnerId());
        vo.setPackageSha256(record.getPackageSha256());
        vo.setSshConfigVersion(record.getSshConfigVersion());
        vo.setVersion(pkg == null ? null : pkg.getVersion());
        vo.setFileName(pkg == null ? null : pkg.getFileName());
        vo.setSizeBytes(record.getSizeBytes() != null ? record.getSizeBytes()
                : (pkg == null ? null : pkg.getSizeBytes()));
        vo.setStatus(record.getStatus());
        vo.setAlreadyPresent(record.getAlreadyPresent());
        vo.setErrorCode(record.getErrorCode());
        vo.setErrorMessage(record.getErrorMessage());
        vo.setRemotePath(record.getRemotePath());
        vo.setOperator(record.getOperator());
        vo.setStartedAt(record.getStartedAt());
        vo.setFinishedAt(record.getFinishedAt());
        vo.setDurationMs(record.getDurationMs());
        return vo;
    }
}
