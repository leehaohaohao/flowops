package com.nexa.flowops.controller;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.PackageDistributionVO;
import com.nexa.flowops.dto.RunnerPackageVO;
import com.nexa.flowops.service.node.NodeAdminGuard;
import com.nexa.flowops.service.nodepackage.PackageDistributionRequestException;
import com.nexa.flowops.service.nodepackage.RunnerPackageDistributionService;
import com.nexa.flowops.service.nodepackage.RunnerPackageException;
import com.nexa.flowops.service.nodepackage.RunnerPackageFailure;
import com.nexa.flowops.service.nodepackage.RunnerPackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布包接口测试（阶段 2 P3）：六个接口都只允许超级管理员，上传消息与既有标记正确，
 * 分发请求级失败透传 {@code code}/{@code msg}。
 */
class NodePackageControllerTest {

    private static final String SHA256 = "a".repeat(64);
    private static final String RUNNER_ID = "runner-1";

    private RunnerPackageService packageService;
    private RunnerPackageDistributionService distributionService;
    private NodeAdminGuard guard;
    private NodePackageController controller;

    @BeforeEach
    void setUp() {
        packageService = mock(RunnerPackageService.class);
        distributionService = mock(RunnerPackageDistributionService.class);
        guard = mock(NodeAdminGuard.class);
        controller = new NodePackageController(packageService, distributionService, guard);
    }

    @Test
    void everyEndpointRejectsNonSuperAdmin() {
        when(guard.isSuperAdmin()).thenReturn(false);

        assertEquals(403, controller.upload(file()).getCode());
        assertEquals(403, controller.listPackages().getCode());
        assertEquals(403, controller.getPackage(SHA256).getCode());
        assertEquals(403, controller.distribute(RUNNER_ID, SHA256).getCode());
        assertEquals(403, controller.listDistributions(RUNNER_ID).getCode());
        assertEquals(403, controller.getDistribution(RUNNER_ID, 1L).getCode());
        assertTrue(controller.listPackages().getMsg().contains("仅超级管理员可管理节点发布包"));

        verify(packageService, never()).upload(any(), any());
        verify(distributionService, never()).distribute(any(), any(), any());
    }

    @Test
    void uploadReportsNewPackageAndPassesOperator() {
        when(guard.isSuperAdmin()).thenReturn(true);
        RunnerPackageVO vo = new RunnerPackageVO();
        vo.setSha256(SHA256);
        vo.setExisting(false);
        vo.setRepaired(false);
        when(packageService.upload(any(), isNull()))
                .thenReturn(new RunnerPackageService.UploadOutcome(vo, false, false, "发布包已上传"));

        Result<RunnerPackageVO> result = controller.upload(file());

        assertEquals(200, result.getCode());
        assertEquals("发布包已上传", result.getMsg());
        assertEquals(SHA256, result.getData().getSha256());
        assertFalse(result.getData().getExisting());
        assertFalse(result.getData().getRepaired());
    }

    @Test
    void uploadReportsExistingPackageIdempotently() {
        when(guard.isSuperAdmin()).thenReturn(true);
        RunnerPackageVO vo = new RunnerPackageVO();
        vo.setSha256(SHA256);
        vo.setExisting(true);
        vo.setRepaired(false);
        when(packageService.upload(any(), isNull()))
                .thenReturn(new RunnerPackageService.UploadOutcome(vo, true, false, "发布包已存在（相同摘要）"));

        Result<RunnerPackageVO> result = controller.upload(file());

        assertEquals("发布包已存在（相同摘要）", result.getMsg());
        assertTrue(result.getData().getExisting());
    }

    @Test
    void uploadReportsRepairedStoreFile() {
        // R2：索引与文件失同步时自愈，属于成功而非错误
        when(guard.isSuperAdmin()).thenReturn(true);
        RunnerPackageVO vo = new RunnerPackageVO();
        vo.setSha256(SHA256);
        vo.setExisting(true);
        vo.setRepaired(true);
        when(packageService.upload(any(), isNull()))
                .thenReturn(new RunnerPackageService.UploadOutcome(vo, true, true, "发布包已存在，已按摘要修复存储文件"));

        Result<RunnerPackageVO> result = controller.upload(file());

        assertEquals(200, result.getCode());
        assertEquals("发布包已存在，已按摘要修复存储文件", result.getMsg());
        assertTrue(result.getData().getRepaired());
    }

    @Test
    void uploadReportsIndexBackfill() {
        when(guard.isSuperAdmin()).thenReturn(true);
        RunnerPackageVO vo = new RunnerPackageVO();
        vo.setSha256(SHA256);
        vo.setExisting(false);
        vo.setRepaired(false);
        when(packageService.upload(any(), isNull())).thenReturn(new RunnerPackageService.UploadOutcome(
                vo, false, false, "发布包已补登记（文件已存在且摘要一致）"));

        Result<RunnerPackageVO> result = controller.upload(file());

        assertEquals("发布包已补登记（文件已存在且摘要一致）", result.getMsg());
        assertFalse(result.getData().getExisting());
    }

    @Test
    void listsAndReadsPackages() {
        when(guard.isSuperAdmin()).thenReturn(true);
        RunnerPackageVO vo = new RunnerPackageVO();
        vo.setSha256(SHA256);
        when(packageService.list()).thenReturn(List.of(vo));
        when(packageService.get(SHA256)).thenReturn(vo);

        assertEquals(1, controller.listPackages().getData().size());
        assertEquals(SHA256, controller.getPackage(SHA256).getData().getSha256());
        assertNull(controller.listPackages().getData().get(0).getExisting(), "列表不返回 existing 标记");
    }

    @Test
    void distributeReturnsRecordForPolling() {
        when(guard.isSuperAdmin()).thenReturn(true);
        PackageDistributionVO vo = new PackageDistributionVO();
        vo.setId(11L);
        vo.setStatus("PENDING");
        when(distributionService.distribute(eq(RUNNER_ID), eq(SHA256), isNull())).thenReturn(vo);

        Result<PackageDistributionVO> result = controller.distribute(RUNNER_ID, SHA256);

        assertEquals(200, result.getCode());
        assertEquals(11L, result.getData().getId());
        assertEquals("PENDING", result.getData().getStatus());
    }

    @Test
    void requestLevelFailuresKeepBusinessCodeAndMessage() {
        when(guard.isSuperAdmin()).thenReturn(true);
        when(packageService.get("bad-sha"))
                .thenThrow(new RunnerPackageException(RunnerPackageFailure.SHA256_INVALID));
        when(distributionService.listForNode("ghost"))
                .thenThrow(PackageDistributionRequestException.nodeNotRegistered("ghost"));

        RunnerPackageException invalid = assertThrows(RunnerPackageException.class,
                () -> controller.getPackage("bad-sha"));
        assertEquals(400, invalid.getCode());
        assertEquals("sha256 格式非法", invalid.getMessage());

        PackageDistributionRequestException notRegistered = assertThrows(
                PackageDistributionRequestException.class, () -> controller.listDistributions("ghost"));
        assertEquals(404, notRegistered.getCode());
        assertEquals("节点未登记: ghost", notRegistered.getMessage());
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "flowops-executor-0.7.0-linux-amd64.tar.gz",
                "application/gzip", new byte[]{1, 2, 3});
    }
}
