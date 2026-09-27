package com.nexa.flowops.controller;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.SshTargetRequest;
import com.nexa.flowops.dto.SshTargetVO;
import com.nexa.flowops.dto.SshTestResultVO;
import com.nexa.flowops.service.node.NodeAdminGuard;
import com.nexa.flowops.service.node.NodeSshService;
import com.nexa.flowops.service.node.SshTargetException;
import com.nexa.flowops.service.node.SshTestResultCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SSH 接口的授权与响应契约测试：仅超级管理员、未配置返回 data=null、
 * 连接失败仍以 code=200 + 结果码返回（便于前端区分接口失败与 SSH 失败）。
 */
class NodeSshControllerTest {

    private static final String RUNNER_ID = "runner-1";

    private final NodeSshService nodeSshService = mock(NodeSshService.class);
    private final NodeAdminGuard nodeAdminGuard = mock(NodeAdminGuard.class);
    private final NodeSshController controller = new NodeSshController(nodeSshService, nodeAdminGuard);

    @Test
    void rejectsEveryOperationForNonSuperAdmin() {
        when(nodeAdminGuard.isSuperAdmin()).thenReturn(false);

        assertEquals(403, controller.getTarget(RUNNER_ID).getCode());
        assertEquals(403, controller.saveTarget(RUNNER_ID, new SshTargetRequest()).getCode());
        assertEquals(403, controller.testConnection(RUNNER_ID).getCode());
        // 未授权时不得触达业务逻辑
        verifyNoInteractions(nodeSshService);
    }

    @Test
    void returnsNullDataWhenTargetNotConfigured() {
        when(nodeAdminGuard.isSuperAdmin()).thenReturn(true);
        when(nodeSshService.get(RUNNER_ID)).thenReturn(null);

        Result<SshTargetVO> result = controller.getTarget(RUNNER_ID);

        assertEquals(200, result.getCode());
        assertNull(result.getData());
    }

    @Test
    void returnsSavedTarget() {
        when(nodeAdminGuard.isSuperAdmin()).thenReturn(true);
        SshTargetVO vo = new SshTargetVO();
        vo.setRunnerId(RUNNER_ID);
        vo.setHost("10.0.0.5");
        when(nodeSshService.save(eq(RUNNER_ID), any())).thenReturn(vo);

        Result<SshTargetVO> result = controller.saveTarget(RUNNER_ID, new SshTargetRequest());

        assertEquals(200, result.getCode());
        assertEquals("10.0.0.5", result.getData().getHost());
    }

    @Test
    void returnsProbeFailureAsBusinessResultNotApiError() {
        when(nodeAdminGuard.isSuperAdmin()).thenReturn(true);
        when(nodeSshService.test(RUNNER_ID)).thenReturn(new SshTestResultVO(
                SshTestResultCode.AUTH_FAILED.name(), SshTestResultCode.AUTH_FAILED.message(),
                LocalDateTime.now(), 1200L));

        Result<SshTestResultVO> result = controller.testConnection(RUNNER_ID);

        assertEquals(200, result.getCode());
        assertEquals(SshTestResultCode.AUTH_FAILED.name(), result.getData().getResultCode());
    }

    @Test
    void propagatesConfigurationValidationFailureAsBusinessException() {
        when(nodeAdminGuard.isSuperAdmin()).thenReturn(true);
        when(nodeSshService.get("ghost")).thenThrow(SshTargetException.notRegistered("ghost"));

        SshTargetException e = assertThrows(SshTargetException.class, () -> controller.getTarget("ghost"));

        assertEquals(SshTargetException.CODE_NOT_REGISTERED, e.getCode());
    }
}
