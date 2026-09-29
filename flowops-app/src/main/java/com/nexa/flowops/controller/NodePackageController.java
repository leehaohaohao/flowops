package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.PackageDistributionVO;
import com.nexa.flowops.dto.RunnerPackageVO;
import com.nexa.flowops.service.node.NodeAdminGuard;
import com.nexa.flowops.service.nodepackage.RunnerPackageDistributionService;
import com.nexa.flowops.service.nodepackage.RunnerPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 节点发布包接口（阶段 2 P3）：上传/列表/详情 + 按节点分发与分发记录查询。
 *
 * <p>六个接口**仅超级管理员**，与 {@code /api/nodes/registry/**} 同一判定（{@link NodeAdminGuard}）；
 * 契约见 {@code docs/frontend-api/node-package-api.md}。上传校验失败经 {@code GlobalExceptionHandler}
 * 返回 {@code code=400} + 固定中文 {@code msg}；分发是异步业务，连接/传输失败以记录里的
 * {@code errorCode}/{@code errorMessage} 表达，不作为接口失败。
 *
 * <p>路由说明：{@code /api/nodes/packages} 与既有 {@code /api/nodes/{runnerId}} 同前缀，
 * Spring 的字面量模式优先，不会被后者截获（与 {@code /api/nodes/registry} 的共存方式相同）。
 */
@RestController
@RequiredArgsConstructor
public class NodePackageController {

    private static final String FORBIDDEN_MESSAGE = "仅超级管理员可管理节点发布包";

    private final RunnerPackageService packageService;
    private final RunnerPackageDistributionService distributionService;
    private final NodeAdminGuard nodeAdminGuard;

    // ==================== 发布包上传与查询 ====================

    /** 上传发布包（multipart 字段名 file）；相同摘要重复上传返回既有记录。 */
    @PostMapping(value = "/api/nodes/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<RunnerPackageVO> upload(@RequestPart(name = "file", required = false) MultipartFile file) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        RunnerPackageService.UploadOutcome outcome = packageService.upload(file, currentOperator());
        // 成功文案由服务层按 R2 的分支给出（全新/已存在/已修复/已补登记）
        return Result.ok(outcome.message(), outcome.packageInfo());
    }

    /** 已存储发布包列表（按上传时间倒序）。 */
    @GetMapping("/api/nodes/packages")
    public Result<List<RunnerPackageVO>> listPackages() {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(packageService.list());
    }

    /** 单个发布包详情。 */
    @GetMapping("/api/nodes/packages/{sha256}")
    public Result<RunnerPackageVO> getPackage(@PathVariable String sha256) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(packageService.get(sha256));
    }

    // ==================== 分发 ====================

    /** 触发一次分发（异步）：立即返回记录，前端轮询单条记录。 */
    @PostMapping("/api/nodes/registry/{runnerId}/packages/{sha256}/distribute")
    public Result<PackageDistributionVO> distribute(@PathVariable String runnerId,
                                                    @PathVariable String sha256) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(distributionService.distribute(runnerId, sha256, currentOperator()));
    }

    /** 该节点各包的最新一次分发记录。 */
    @GetMapping("/api/nodes/registry/{runnerId}/packages")
    public Result<List<PackageDistributionVO>> listDistributions(@PathVariable String runnerId) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(distributionService.listForNode(runnerId));
    }

    /** 轮询单条分发记录。 */
    @GetMapping("/api/nodes/registry/{runnerId}/packages/distributions/{id}")
    public Result<PackageDistributionVO> getDistribution(@PathVariable String runnerId,
                                                         @PathVariable Long id) {
        if (!nodeAdminGuard.isSuperAdmin()) {
            return Result.fail(403, FORBIDDEN_MESSAGE);
        }
        return Result.ok(distributionService.getRecord(runnerId, id));
    }

    private String currentOperator() {
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            return null;
        }
    }
}
