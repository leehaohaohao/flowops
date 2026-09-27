package com.nexa.flowops.controller;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.SshTargetRequest;
import com.nexa.flowops.dto.SshTargetVO;
import com.nexa.flowops.dto.SshTestResultVO;
import com.nexa.flowops.service.node.NodeAdminGuard;
import com.nexa.flowops.service.node.NodeSshService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 节点宿主机的 SSH 设置与连接测试（阶段 1）。
 *
 * <p>三个接口**仅超级管理员**（与 {@code /api/nodes/registry/**} 同一判定）；契约见
 * docs/2026-09-27-node-ssh-connection-plan.md：
 * GET 读取设置、PUT 全量保存、POST /test 用已保存设置做一次只读连接验证。
 * 业务校验失败经 {@code GlobalExceptionHandler} 返回 400/404；连接失败是业务结果，用结果码表达。
 */
@RestController
@RequestMapping("/api/nodes/registry/{runnerId}/ssh")
@RequiredArgsConstructor
public class NodeSshController {

    private static final String FORBIDDEN_MESSAGE = "仅超级管理员可管理节点 SSH 设置";

    private final NodeSshService nodeSshService;
    private final NodeAdminGuard nodeAdminGuard;

    /** 读取 SSH 设置与最近一次测试结果；未配置时 data 为 null。 */
    @GetMapping
    public Result<SshTargetVO> getTarget(@PathVariable String runnerId) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(nodeSshService.get(runnerId));
    }

    /** 新增或全量覆盖 SSH 设置（私钥只传别名，不传内容）。 */
    @PutMapping
    public Result<SshTargetVO> saveTarget(@PathVariable String runnerId,
                                         @RequestBody(required = false) SshTargetRequest request) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok("SSH 设置已保存", nodeSshService.save(runnerId, request));
    }

    /** 用已保存的设置执行一次连接验证（严格主机密钥、只读、不写远端）。 */
    @PostMapping("/test")
    public Result<SshTestResultVO> testConnection(@PathVariable String runnerId) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(nodeSshService.test(runnerId));
    }
}
