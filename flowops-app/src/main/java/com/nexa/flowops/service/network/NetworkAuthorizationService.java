package com.nexa.flowops.service.network;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.NetworkProjectGrant;
import com.nexa.flowops.entity.ProjectDefaultNetwork;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.mapper.NetworkProjectGrantMapper;
import com.nexa.flowops.mapper.ProjectDefaultNetworkMapper;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.ProjectMapper;
import com.nexa.flowops.permission.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络授权与项目默认值（B3）。
 *
 * <p>权限规则（见计划第三节）：
 * <ul>
 *   <li>全局网络管理（创建/导入/删除/授权撤销）仅超级管理员</li>
 *   <li>{@code GET /api/projects/{id}/networks}：超级管理员、该项目主管、该项目 EDIT_CONFIG 用户</li>
 *   <li>{@code GET/PUT /api/projects/{id}/default-network}：超级管理员或该项目主管，且只能选本项目已授权网络</li>
 *   <li>撤销授权时，若仍有服务引用或作为项目默认值则拒绝</li>
 * </ul>
 * 项目权限查询只调用现有 {@code flowops-permission} 服务，不修改该模块。
 */
@Service
public class NetworkAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(NetworkAuthorizationService.class);

    private final NetworkProjectGrantMapper grantMapper;
    private final ProjectDefaultNetworkMapper defaultNetworkMapper;
    private final DeployServiceMapper serviceMapper;
    private final DockerNetworkService networkService;
    private final PermissionService permissionService;
    private final ProjectMapper projectMapper;

    public NetworkAuthorizationService(NetworkProjectGrantMapper grantMapper,
                                       ProjectDefaultNetworkMapper defaultNetworkMapper,
                                       DeployServiceMapper serviceMapper,
                                       DockerNetworkService networkService,
                                       PermissionService permissionService,
                                       ProjectMapper projectMapper) {
        this.grantMapper = grantMapper;
        this.defaultNetworkMapper = defaultNetworkMapper;
        this.serviceMapper = serviceMapper;
        this.networkService = networkService;
        this.permissionService = permissionService;
        this.projectMapper = projectMapper;
    }

    // ==================== 权限判定 ====================

    /** 仅超级管理员（未登录由 Sa-Token 抛 NotLoginException，统一返回 401） */
    public void requireSuperAdmin() {
        if (!permissionService.isSuperAdmin()) {
            throw NetworkException.forbidden("仅超级管理员可管理主节点网络");
        }
    }

    /** 项目可选网络读取：超管 / 该项目主管 / 该项目 EDIT_CONFIG 用户 */
    public void requireProjectNetworkRead(Long projectId) {
        SysUser user = permissionService.getCurrentUser();
        if (user == null) {
            throw NetworkException.forbidden("用户不存在");
        }
        if (user.getIsSuperAdmin() == 1) {
            return;
        }
        if (permissionService.isSupervisor(user.getId(), projectId)) {
            return;
        }
        if (permissionService.getEffectivePermissions(user.getId(), projectId).contains("EDIT_CONFIG")) {
            return;
        }
        throw NetworkException.forbidden("无权查看该项目可选网络");
    }

    /** 项目默认网络写入：超管 / 该项目主管 */
    public void requireProjectDefaultWrite(Long projectId) {
        SysUser user = permissionService.getCurrentUser();
        if (user == null) {
            throw NetworkException.forbidden("用户不存在");
        }
        if (user.getIsSuperAdmin() == 1) {
            return;
        }
        if (permissionService.isSupervisor(user.getId(), projectId)) {
            return;
        }
        throw NetworkException.forbidden("仅超级管理员或该项目主管可修改项目默认网络");
    }

    // ==================== 授权 ====================

    /** 授予项目网络使用权（幂等） */
    public void grant(Long networkId, Long projectId) {
        requireSuperAdmin();
        networkService.requireRegistered(networkId);
        requireProject(projectId);

        NetworkProjectGrant existing = findGrant(networkId, projectId);
        if (existing != null) {
            return;
        }
        NetworkProjectGrant grant = new NetworkProjectGrant();
        grant.setNetworkId(networkId);
        grant.setProjectId(projectId);
        grantMapper.insert(grant);
        log.info("已授权项目使用网络: projectId={}, networkId={}", projectId, networkId);
    }

    /** 撤销项目网络使用权：仍有服务引用或作为默认值时拒绝 */
    public void revoke(Long networkId, Long projectId) {
        requireSuperAdmin();
        networkService.requireRegistered(networkId);
        requireProject(projectId);

        ProjectDefaultNetwork defaultNetwork = defaultNetworkMapper.selectById(projectId);
        if (defaultNetwork != null && networkId.equals(defaultNetwork.getNetworkId())) {
            throw NetworkException.conflict("该网络是该项目默认网络，请先清除默认值再撤销授权");
        }
        long refs = serviceMapper.selectCount(new LambdaQueryWrapper<DeployService>()
                .eq(DeployService::getProjectId, projectId)
                .eq(DeployService::getNetworkId, networkId));
        if (refs > 0) {
            throw NetworkException.conflict("该项目仍有 " + refs + " 个服务使用该网络，请先解除服务网络选择");
        }

        grantMapper.delete(new LambdaQueryWrapper<NetworkProjectGrant>()
                .eq(NetworkProjectGrant::getNetworkId, networkId)
                .eq(NetworkProjectGrant::getProjectId, projectId));
        log.info("已撤销项目网络授权: projectId={}, networkId={}", projectId, networkId);
    }

    // ==================== 项目默认值 ====================

    public Long getDefaultNetworkId(Long projectId) {
        requireProjectNetworkRead(projectId);
        requireProject(projectId);
        ProjectDefaultNetwork defaultNetwork = defaultNetworkMapper.selectById(projectId);
        return defaultNetwork == null ? null : defaultNetwork.getNetworkId();
    }

    /** 设置或清除项目默认网络；只能选本项目已授权网络 */
    public void setDefaultNetwork(Long projectId, Long networkId) {
        requireProjectDefaultWrite(projectId);
        requireProject(projectId);

        if (networkId == null) {
            defaultNetworkMapper.deleteById(projectId);
            log.info("已清除项目默认网络: projectId={}", projectId);
            return;
        }
        networkService.requireRegistered(networkId);
        if (!grantedNetworkIds(projectId).contains(networkId)) {
            throw NetworkException.invalid("只能选择本项目已授权的网络");
        }
        ProjectDefaultNetwork existing = defaultNetworkMapper.selectById(projectId);
        ProjectDefaultNetwork entity = new ProjectDefaultNetwork();
        entity.setProjectId(projectId);
        entity.setNetworkId(networkId);
        if (existing == null) {
            defaultNetworkMapper.insert(entity);
        } else {
            defaultNetworkMapper.updateById(entity);
        }
        log.info("已设置项目默认网络: projectId={}, networkId={}", projectId, networkId);
    }

    // ==================== 项目可选网络 ====================

    /** 该项目已授权且在主节点 Docker 中实际存在的网络；缺失资源不作为候选 */
    public List<NetworkOverview> listProjectNetworks(Long projectId) {
        requireProjectNetworkRead(projectId);
        requireProject(projectId);

        List<NetworkOverview> result = new ArrayList<>();
        for (Long networkId : grantedNetworkIds(projectId)) {
            NetworkOverview overview;
            try {
                overview = networkService.overview(networkId);
            } catch (NetworkException e) {
                continue; // 授权残留但网络登记已删除：不作为候选
            }
            if (DockerNetworkService.STATUS_PRESENT.equals(overview.dockerStatus())) {
                result.add(overview);
            }
        }
        return result;
    }

    public List<Long> grantedNetworkIds(Long projectId) {
        return grantMapper.selectList(new LambdaQueryWrapper<NetworkProjectGrant>()
                        .eq(NetworkProjectGrant::getProjectId, projectId))
                .stream()
                .map(NetworkProjectGrant::getNetworkId)
                .toList();
    }

    /** 服务保存/部署校验用：网络是否已授权给该项目 */
    public boolean isGranted(Long projectId, Long networkId) {
        return findGrant(networkId, projectId) != null;
    }

    private NetworkProjectGrant findGrant(Long networkId, Long projectId) {
        if (networkId == null || projectId == null) {
            return null;
        }
        return grantMapper.selectOne(new LambdaQueryWrapper<NetworkProjectGrant>()
                .eq(NetworkProjectGrant::getNetworkId, networkId)
                .eq(NetworkProjectGrant::getProjectId, projectId));
    }

    private void requireProject(Long projectId) {
        if (projectId == null) {
            throw NetworkException.notRegistered("项目不存在");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw NetworkException.notRegistered("项目不存在: " + projectId);
        }
    }
}
