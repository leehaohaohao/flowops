package com.nexa.flowops.service.nodepackage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexa.flowops.dto.PackageDistributionVO;
import com.nexa.flowops.entity.NexaNode;
import com.nexa.flowops.entity.NexaNodePackage;
import com.nexa.flowops.entity.NexaNodePackageDistribution;
import com.nexa.flowops.entity.NexaNodeSshTarget;
import com.nexa.flowops.mapper.NexaNodeMapper;
import com.nexa.flowops.mapper.NexaNodePackageDistributionMapper;
import com.nexa.flowops.mapper.NexaNodePackageMapper;
import com.nexa.flowops.mapper.NexaNodeSshTargetMapper;
import com.nexa.flowops.service.node.SshTestResultCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分发编排测试（阶段 2 P3）：前置条件、幂等与并发、异步状态机、错误码落库、重启恢复。
 *
 * <p>不连接真实 SSH：{@link RunnerPackageDistributor} 用 mock 控制成功/失败/阻塞，
 * 真实 SFTP 链路见 {@code RunnerPackageDistributionLinkTest}。
 */
class RunnerPackageDistributionServiceTest {

    private static final String RUNNER_ID = "runner-1";
    private static final String SHA256 = "d".repeat(64);
    private static final String FILE_NAME = "flowops-executor-0.7.0-linux-amd64.tar.gz";
    private static final String REMOTE_PATH = "/opt/flowops/runner/packages/" + SHA256 + ".tar.gz";

    private NexaNodeMapper nodeMapper;
    private NexaNodeSshTargetMapper targetMapper;
    private NexaNodePackageMapper packageMapper;
    private NexaNodePackageDistributionMapper distributionMapper;
    private RunnerPackageDistributor distributor;
    private RunnerPackageSettings settings;
    private RunnerPackageDistributionService service;
    /** 记录所有 update wrapper，便于断言异步写入的内容 */
    private final List<Wrapper<NexaNodePackageDistribution>> capturedUpdates =
            Collections.synchronizedList(new ArrayList<>());
    /** 模拟库表：insert 的记录按 id 回读，后台任务才能读到自己的记录 */
    private final java.util.Map<Long, NexaNodePackageDistribution> insertedRecords =
            new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        // 纯单测无 Spring 上下文：LambdaUpdateWrapper 需要实体元信息缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NexaNodePackageDistribution.class);
        TableInfoHelper.initTableInfo(assistant, NexaNodePackage.class);

        nodeMapper = mock(NexaNodeMapper.class);
        targetMapper = mock(NexaNodeSshTargetMapper.class);
        packageMapper = mock(NexaNodePackageMapper.class);
        distributionMapper = mock(NexaNodePackageDistributionMapper.class);
        distributor = mock(RunnerPackageDistributor.class);
        settings = new RunnerPackageSettings();
        settings.setMaxConcurrentDistributions(2);
        settings.setDistributionQueueCapacity(4);

        RunnerPackageService packageService = mock(RunnerPackageService.class);
        when(packageService.requireEntity(SHA256)).thenReturn(packageEntity());
        when(distributor.finalRemotePath(SHA256)).thenReturn(REMOTE_PATH);

        LocalRunnerPackageStore packageStore = mock(LocalRunnerPackageStore.class);
        when(packageStore.packagePath(SHA256)).thenReturn(Path.of("/data/flowops/runner-packages", SHA256 + ".tar.gz"));

        service = new RunnerPackageDistributionService(nodeMapper, targetMapper, packageMapper,
                distributionMapper, packageService, distributor, settings, packageStore);

        when(nodeMapper.selectById(RUNNER_ID)).thenReturn(new NexaNode());
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(verifiedTarget());
        when(packageMapper.selectById(SHA256)).thenReturn(packageEntity());
        when(distributionMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(distributionMapper.insert(any(NexaNodePackageDistribution.class))).thenAnswer(invocation -> {
            NexaNodePackageDistribution inserted = invocation.getArgument(0);
            inserted.setId(7L);
            insertedRecords.put(7L, inserted);
            return 1;
        });
        // 后台任务会按 id 回读记录：模拟库表以便任务真的执行到终态（null 安全，便于用例重新 stub）
        when(distributionMapper.selectById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0, Long.class);
            return id == null ? null : insertedRecords.get(id);
        });
        when(distributionMapper.update(isNull(), any())).thenAnswer(invocation -> {
            capturedUpdates.add(invocation.getArgument(1));
            return 1;
        });
    }

    // ==================== R1：绑定已验证的 SSH 配置版本 ====================

    @Test
    void bindsVerifiedSshConfigVersionWhenQueueing() {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenReturn(new RunnerPackageDistributor.Outcome(false, REMOTE_PATH));

        PackageDistributionVO queued = service.distribute(RUNNER_ID, SHA256, "admin");

        assertEquals(7L, queued.getSshConfigVersion(), "记录应绑定当时的 config_version");
        ArgumentCaptor<NexaNodePackageDistribution> inserted =
                ArgumentCaptor.forClass(NexaNodePackageDistribution.class);
        verify(distributionMapper).insert(inserted.capture());
        assertEquals(Long.valueOf(7L), inserted.getValue().getSshConfigVersion());
    }

    @Test
    void failsWhenSshConfigChangedAfterQueueing() throws Exception {
        // 排队后管理员覆盖了 SSH 设置：config_version 递增，已排队的任务必须失效且不开会话。
        // 第一次读取（请求级前置）看到已通过测试的 v7，第二次读取（后台执行前）看到被改过的 v8。
        NexaNodeSshTarget changed = verifiedTarget();
        changed.setConfigVersion(8L);
        changed.setHost("10.0.0.9");
        changed.setKeyAlias("another-key");
        changed.setLastResultCode(null);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(verifiedTarget(), changed);

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(terminal.getParamNameValuePairs().containsValue("SSH_CONFIG_CHANGED"));
        assertTrue(terminal.getParamNameValuePairs()
                .containsValue(PackageDistributionErrorCode.SSH_CONFIG_CHANGED.message()));
        verify(distributor, never()).distribute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void failsWhenSshTargetWasDeletedAfterQueueing() throws Exception {
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(verifiedTarget(), null);

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("SSH_CONFIG_CHANGED"));
        verify(distributor, never()).distribute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void failsWhenLatestSshTestIsNoLongerConnected() throws Exception {
        // 版本没变但最近一次测试失败（管理员重测失败）：同样不得分发。
        // 第一次读取仍是 CONNECTED（请求级前置通过），第二次读取已是失败结果。
        NexaNodeSshTarget retested = verifiedTarget();
        retested.setLastResultCode(SshTestResultCode.AUTH_FAILED.name());
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(verifiedTarget(), retested);

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("SSH_NOT_VERIFIED"));
        assertTrue(terminal.getParamNameValuePairs()
                .containsValue(PackageDistributionErrorCode.SSH_NOT_VERIFIED.message()));
        verify(distributor, never()).distribute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void failsLegacyRecordWithoutBoundSshVersion() throws Exception {
        // V3_1_9 之前的历史记录没有绑定版本：不能假定它对应哪一版设置。
        // 这里让后台回读拿到一条 ssh_config_version = NULL 的记录。
        when(distributionMapper.selectById(7L)).thenAnswer(invocation -> {
            NexaNodePackageDistribution legacy = new NexaNodePackageDistribution();
            legacy.setId(7L);
            legacy.setRunnerId(RUNNER_ID);
            legacy.setPackageSha256(SHA256);
            legacy.setStatus("PENDING");
            legacy.setSizeBytes(993L);
            legacy.setSshConfigVersion(null);
            return legacy;
        });

        service.distribute(RUNNER_ID, SHA256, "admin");

        assertTrue(awaitTerminalUpdate().getParamNameValuePairs().containsValue("SSH_CONFIG_CHANGED"));
        verify(distributor, never()).distribute(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void distributesWithBoundVersionWhenNothingChanged() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenReturn(new RunnerPackageDistributor.Outcome(false, REMOTE_PATH));

        PackageDistributionVO queued = service.distribute(RUNNER_ID, SHA256, "admin");
        assertEquals(7L, queued.getSshConfigVersion());

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("SUCCEEDED"));
        // 会话使用后台任务读到的那一份目标（版本一致才会走到这里）
        verify(distributor).distribute(eq(RUNNER_ID), eq(SHA256), any(), anyLong(),
                eq(verifiedTarget()), any());
    }

    // ==================== 请求级前置 ====================

    @Test
    void rejectsUnregisteredNode() {
        when(nodeMapper.selectById("ghost")).thenReturn(null);

        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.distribute("ghost", SHA256, "admin"));

        assertEquals(PackageDistributionRequestException.CODE_NOT_FOUND, e.getCode());
        assertEquals("节点未登记: ghost", e.getMessage());
    }

    @Test
    void rejectsNodeWithoutSshTarget() {
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(null);

        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.distribute(RUNNER_ID, SHA256, "admin"));

        assertEquals(400, e.getCode());
        assertEquals("尚未配置 SSH 目标", e.getMessage());
    }

    @Test
    void rejectsNodeWithoutHostKeyAlgorithm() {
        NexaNodeSshTarget target = verifiedTarget();
        target.setHostKeyAlgorithm(null);
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(target);

        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.distribute(RUNNER_ID, SHA256, "admin"));

        assertEquals("尚未选择主机密钥算法，请先补全 SSH 设置", e.getMessage());
    }

    @Test
    void rejectsNodeWhoseSshTestHasNotPassed() {
        NexaNodeSshTarget target = verifiedTarget();
        target.setLastResultCode(SshTestResultCode.HOST_KEY_MISMATCH.name());
        when(targetMapper.selectById(RUNNER_ID)).thenReturn(target);

        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.distribute(RUNNER_ID, SHA256, "admin"));

        assertEquals("该节点 SSH 尚未通过连接测试，请先测试连接", e.getMessage());
        verify(distributionMapper, never()).insert(any(NexaNodePackageDistribution.class));
    }

    @Test
    void rejectsUnknownPackage() {
        RunnerPackageService packageService = mock(RunnerPackageService.class);
        when(packageService.requireEntity(SHA256))
                .thenThrow(new RunnerPackageException(RunnerPackageFailure.PACKAGE_NOT_FOUND, SHA256));
        RunnerPackageDistributionService local = new RunnerPackageDistributionService(nodeMapper, targetMapper,
                packageMapper, distributionMapper, packageService, distributor, settings,
                mock(LocalRunnerPackageStore.class));

        RunnerPackageException e = assertThrows(RunnerPackageException.class,
                () -> local.distribute(RUNNER_ID, SHA256, "admin"));

        assertEquals(RunnerPackageFailure.PACKAGE_NOT_FOUND, e.failure());
    }

    // ==================== 幂等与并发 ====================

    @Test
    void returnsExistingRecordWhenSameDigestIsInFlight() {
        when(distributionMapper.selectList(any())).thenReturn(List.of(inFlightRecord(7L, SHA256)));

        PackageDistributionVO vo = service.distribute(RUNNER_ID, SHA256, "admin");

        assertEquals(7L, vo.getId());
        assertEquals("UPLOADING", vo.getStatus());
        verify(distributionMapper, never()).insert(any(NexaNodePackageDistribution.class));
    }

    @Test
    void rejectsWhenAnotherDigestIsInFlight() {
        when(distributionMapper.selectList(any())).thenReturn(List.of(inFlightRecord(8L, "e".repeat(64))));

        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.distribute(RUNNER_ID, SHA256, "admin"));

        assertEquals(PackageDistributionRequestException.CODE_CONFLICT, e.getCode());
        assertEquals("该节点已有分发任务进行中", e.getMessage());
    }

    @Test
    void rejectsWhenQueueIsSaturated() throws Exception {
        settings.setMaxConcurrentDistributions(1);
        settings.setDistributionQueueCapacity(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(distributor.distribute(any(), any(), any(), anyLong(), any(), any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new RunnerPackageDistributor.Outcome(false, REMOTE_PATH);
        });
        for (String runner : List.of("runner-1", "runner-2", "runner-3")) {
            when(nodeMapper.selectById(runner)).thenReturn(new NexaNode());
            when(targetMapper.selectById(runner)).thenReturn(verifiedTarget());
        }

        service.distribute("runner-1", SHA256, "admin");
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "唯一工作线程应已开始执行");
        service.distribute("runner-2", SHA256, "admin"); // 进入容量为 1 的队列
        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.distribute("runner-3", SHA256, "admin"));

        assertEquals("分发队列已满，请稍后重试", e.getMessage());
        release.countDown();
    }

    // ==================== 异步状态机 ====================

    @Test
    void marksSucceededAndRecordsRemotePath() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenReturn(new RunnerPackageDistributor.Outcome(false, REMOTE_PATH));

        PackageDistributionVO queued = service.distribute(RUNNER_ID, SHA256, "admin");
        assertEquals("PENDING", queued.getStatus());
        assertEquals(REMOTE_PATH, queued.getRemotePath());

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getSqlSet().contains("status"));
        assertTrue(terminal.getParamNameValuePairs().containsValue("SUCCEEDED"));
        assertTrue(terminal.getParamNameValuePairs().containsValue(false), "already_present=false");
        assertTrue(terminal.getParamNameValuePairs().containsValue(REMOTE_PATH));
    }

    @Test
    void recordsStageTransitionsInOrder() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<PackageDistributionStatus> stage = invocation.getArgument(5);
                    stage.accept(PackageDistributionStatus.UPLOADING);
                    stage.accept(PackageDistributionStatus.VERIFYING);
                    return new RunnerPackageDistributor.Outcome(false, REMOTE_PATH);
                });

        service.distribute(RUNNER_ID, SHA256, "admin");
        awaitTerminalUpdate();

        List<Object> statuses = new ArrayList<>();
        for (Wrapper<NexaNodePackageDistribution> wrapper : List.copyOf(capturedUpdates)) {
            ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values()
                    .forEach(value -> {
                        if (value instanceof String text && List.of("UPLOADING", "VERIFYING").contains(text)) {
                            statuses.add(text);
                        }
                    });
        }
        assertEquals(List.of("UPLOADING", "VERIFYING"), statuses, "阶段只能单调前进");
    }

    @Test
    void marksAlreadyPresentWhenTargetHasSamePackage() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenReturn(new RunnerPackageDistributor.Outcome(true, REMOTE_PATH));

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("SUCCEEDED"));
        assertTrue(terminal.getParamNameValuePairs().containsValue(true), "already_present=true");
    }

    @Test
    void recordsSshStageFailureWithPhaseOneErrorCode() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenThrow(new PackageDistributionFailure(SshTestResultCode.HOST_KEY_MISMATCH));

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(terminal.getParamNameValuePairs().containsValue("HOST_KEY_MISMATCH"),
                "连接阶段沿用阶段 1 结果码");
        assertTrue(terminal.getParamNameValuePairs().containsValue(SshTestResultCode.HOST_KEY_MISMATCH.message()));
    }

    @Test
    void recordsRemoteStageFailureWithDistributionErrorCode() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenThrow(new PackageDistributionFailure(PackageDistributionErrorCode.REMOTE_CHECKSUM_MISMATCH));

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("REMOTE_CHECKSUM_MISMATCH"));
        assertTrue(terminal.getParamNameValuePairs()
                .containsValue(PackageDistributionErrorCode.REMOTE_CHECKSUM_MISMATCH.message()));
    }

    @Test
    void recordsInternalErrorWithoutLeakingExceptionDetails() throws Exception {
        when(distributor.distribute(any(), any(), any(Path.class), anyLong(), any(), any()))
                .thenThrow(new IllegalStateException("内部细节不应出现在记录里"));

        service.distribute(RUNNER_ID, SHA256, "admin");

        AbstractWrapper<?, ?, ?> terminal = awaitTerminalUpdate();
        assertTrue(terminal.getParamNameValuePairs().containsValue("INTERNAL_ERROR"));
        assertFalse(terminal.getParamNameValuePairs().containsValue("内部细节不应出现在记录里"));
    }

    // ==================== 查询与重启恢复 ====================

    @Test
    void listsOnlyLatestRecordPerPackageForNode() {
        when(distributionMapper.selectList(any())).thenReturn(List.of(
                record(9L, SHA256, "SUCCEEDED"), record(8L, SHA256, "FAILED"),
                record(7L, "f".repeat(64), "FAILED")));
        when(packageMapper.selectBatchIds(any())).thenReturn(List.of(packageEntity()));

        List<PackageDistributionVO> records = service.listForNode(RUNNER_ID);

        assertEquals(2, records.size(), "每个包只返回最新一次记录");
        assertEquals(9L, records.get(0).getId());
        assertEquals(FILE_NAME, records.get(0).getFileName());
    }

    @Test
    void rejectsRecordOfAnotherNode() {
        insertedRecords.put(5L, record(5L, SHA256, "SUCCEEDED"));

        PackageDistributionRequestException e = assertThrows(PackageDistributionRequestException.class,
                () -> service.getRecord("runner-2", 5L));

        assertEquals("分发记录不存在: 5", e.getMessage());
    }

    @Test
    void recoverInterruptedMarksInFlightRecordsAsFailed() {
        service.recoverInterrupted();

        verify(distributionMapper, times(1)).update(isNull(), any());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) capturedUpdates.get(0);
        assertTrue(wrapper.getSqlSet().contains("error_code"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("FAILED"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("MASTER_RESTARTED"));
    }

    // ==================== 工具 ====================

    /** 等待后台任务写入终态（SUCCEEDED/FAILED），最多 5 秒。 */
    private AbstractWrapper<?, ?, ?> awaitTerminalUpdate() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (Wrapper<NexaNodePackageDistribution> wrapper : List.copyOf(capturedUpdates)) {
                AbstractWrapper<?, ?, ?> candidate = (AbstractWrapper<?, ?, ?>) wrapper;
                var values = candidate.getParamNameValuePairs().values();
                if (values.contains("SUCCEEDED") || values.contains("FAILED")) {
                    return candidate;
                }
            }
            Thread.sleep(50);
        }
        fail("未在 5 秒内写入分发终态");
        return null;
    }

    private NexaNodeSshTarget verifiedTarget() {
        NexaNodeSshTarget target = new NexaNodeSshTarget();
        target.setRunnerId(RUNNER_ID);
        target.setHost("10.0.0.5");
        target.setPort(22);
        target.setUsername("root");
        target.setKeyAlias("runner-1");
        target.setHostKeyAlgorithm("ED25519");
        target.setHostKeySha256("SHA256:" + "a".repeat(43));
        target.setLastResultCode(SshTestResultCode.CONNECTED.name());
        // 已通过连接测试的那一版设置：R1 的分发绑定与后台核对都基于它
        target.setConfigVersion(7L);
        return target;
    }

    private NexaNodePackage packageEntity() {
        NexaNodePackage pkg = new NexaNodePackage();
        pkg.setSha256(SHA256);
        pkg.setFileName(FILE_NAME);
        pkg.setVersion("0.7.0");
        pkg.setSizeBytes(993L);
        return pkg;
    }

    private NexaNodePackageDistribution inFlightRecord(Long id, String sha256) {
        return record(id, sha256, "UPLOADING");
    }

    private NexaNodePackageDistribution record(Long id, String sha256, String status) {
        NexaNodePackageDistribution record = new NexaNodePackageDistribution();
        record.setId(id);
        record.setRunnerId(RUNNER_ID);
        record.setPackageSha256(sha256);
        record.setStatus(status);
        record.setSizeBytes(993L);
        assertNotNull(record.getStatus());
        return record;
    }
}
