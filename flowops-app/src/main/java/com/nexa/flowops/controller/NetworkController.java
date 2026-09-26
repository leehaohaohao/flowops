package com.nexa.flowops.controller;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.CreateNetworkRequest;
import com.nexa.flowops.dto.DefaultNetworkRequest;
import com.nexa.flowops.dto.DefaultNetworkVO;
import com.nexa.flowops.dto.ImportableNetworkVO;
import com.nexa.flowops.dto.NetworkVO;
import com.nexa.flowops.dto.ProjectNetworkVO;
import com.nexa.flowops.entity.DockerNetwork;
import com.nexa.flowops.service.network.DockerNetworkService;
import com.nexa.flowops.service.network.NetworkAuthorizationService;
import com.nexa.flowops.service.network.NetworkOverview;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 主节点 Docker 网络管理接口（B3，契约见 docs/2026-09-26-node-scoped-docker-network-design-plan.md 第三节）。
 *
 * <p>鉴权在服务层完成：全局网络管理仅超级管理员；项目视角接口按超管 / 项目主管 / EDIT_CONFIG 判定。
 * 本期网络仅属主节点，接口不涉及 runner 或跨节点通信。
 */
@RestController
public class NetworkController {

    private final DockerNetworkService networkService;
    private final NetworkAuthorizationService authorizationService;

    public NetworkController(DockerNetworkService networkService,
                             NetworkAuthorizationService authorizationService) {
        this.networkService = networkService;
        this.authorizationService = authorizationService;
    }

    // ==================== 全局网络管理（仅超级管理员） ====================

    @GetMapping("/api/networks")
    public Result<List<NetworkVO>> list() {
        authorizationService.requireSuperAdmin();
        return Result.ok(networkService.listOverview().stream().map(this::toNetworkVO).toList());
    }

    @GetMapping("/api/networks/importable")
    public Result<List<ImportableNetworkVO>> importable() {
        authorizationService.requireSuperAdmin();
        return Result.ok(networkService.listImportable().stream().map(item -> {
            ImportableNetworkVO vo = new ImportableNetworkVO();
            vo.setName(item.name());
            vo.setDriver(item.driver());
            return vo;
        }).toList());
    }

    @PostMapping("/api/networks")
    public Result<NetworkVO> create(@RequestBody CreateNetworkRequest req) {
        authorizationService.requireSuperAdmin();
        DockerNetwork created = networkService.createManaged(req.getName(), req.getDisplayName());
        return Result.ok("网络已创建并登记", toNetworkVO(networkService.overview(created.getId())));
    }

    @PostMapping("/api/networks/import")
    public Result<NetworkVO> importNetwork(@RequestBody CreateNetworkRequest req) {
        authorizationService.requireSuperAdmin();
        DockerNetwork imported = networkService.importExisting(req.getName(), req.getDisplayName());
        return Result.ok("网络已导入并登记", toNetworkVO(networkService.overview(imported.getId())));
    }

    @DeleteMapping("/api/networks/{networkId}")
    public Result<Void> delete(@PathVariable Long networkId) {
        authorizationService.requireSuperAdmin();
        networkService.deleteNetwork(networkId);
        return Result.ok("网络已删除");
    }

    @PutMapping("/api/networks/{networkId}/projects/{projectId}")
    public Result<Void> grant(@PathVariable Long networkId, @PathVariable Long projectId) {
        authorizationService.grant(networkId, projectId);
        return Result.ok("已授权该项目使用网络");
    }

    @DeleteMapping("/api/networks/{networkId}/projects/{projectId}")
    public Result<Void> revoke(@PathVariable Long networkId, @PathVariable Long projectId) {
        authorizationService.revoke(networkId, projectId);
        return Result.ok("已撤销该项目网络授权");
    }

    // ==================== 项目视角 ====================

    @GetMapping("/api/projects/{projectId}/networks")
    public Result<List<ProjectNetworkVO>> projectNetworks(@PathVariable Long projectId) {
        return Result.ok(authorizationService.listProjectNetworks(projectId).stream().map(this::toProjectNetworkVO).toList());
    }

    @GetMapping("/api/projects/{projectId}/default-network")
    public Result<DefaultNetworkVO> getDefaultNetwork(@PathVariable Long projectId) {
        return Result.ok(new DefaultNetworkVO(authorizationService.getDefaultNetworkId(projectId)));
    }

    @PutMapping("/api/projects/{projectId}/default-network")
    public Result<Void> setDefaultNetwork(@PathVariable Long projectId, @RequestBody DefaultNetworkRequest req) {
        authorizationService.setDefaultNetwork(projectId, req == null ? null : req.getNetworkId());
        return Result.ok(req == null || req.getNetworkId() == null ? "已清除项目默认网络" : "已设置项目默认网络");
    }

    // ==================== 映射 ====================

    private NetworkVO toNetworkVO(NetworkOverview overview) {
        NetworkVO vo = new NetworkVO();
        vo.setId(overview.network().getId());
        vo.setName(overview.network().getName());
        vo.setDisplayName(overview.network().getDisplayName());
        vo.setSource(overview.network().getSource());
        vo.setDockerStatus(overview.dockerStatus());
        vo.setGrantedProjectCount(overview.grantedProjectCount());
        vo.setServiceRefCount(overview.serviceRefCount());
        vo.setCreateTime(overview.network().getCreateTime());
        return vo;
    }

    private ProjectNetworkVO toProjectNetworkVO(NetworkOverview overview) {
        ProjectNetworkVO vo = new ProjectNetworkVO();
        vo.setId(overview.network().getId());
        vo.setName(overview.network().getName());
        vo.setDisplayName(overview.network().getDisplayName());
        vo.setSource(overview.network().getSource());
        vo.setDockerStatus(overview.dockerStatus());
        return vo;
    }
}
