package com.nexa.flowops.permission.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.permission.entity.*;
import com.nexa.flowops.permission.mapper.*;
import com.nexa.flowops.permission.ExternalDataProvider;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private final SysUserMapper userMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final PermRoleMapper permRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessMapper projectAccessMapper;
    private final ProjectGroupMapper projectGroupMapper;
    private final ExternalDataProvider externalDataProvider;

    public PermissionService(SysUserMapper userMapper,
                             GroupMemberMapper groupMemberMapper,
                             PermRoleMapper permRoleMapper,
                             RolePermissionMapper rolePermissionMapper,
                             ProjectMapper projectMapper,
                             ProjectAccessMapper projectAccessMapper,
                             ProjectGroupMapper projectGroupMapper,
                             ExternalDataProvider externalDataProvider) {
        this.userMapper = userMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.permRoleMapper = permRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.projectMapper = projectMapper;
        this.projectAccessMapper = projectAccessMapper;
        this.projectGroupMapper = projectGroupMapper;
        this.externalDataProvider = externalDataProvider;
    }

    /**
     * 获取当前登录用户
     */
    public SysUser getCurrentUser() {
        String username = StpUtil.getLoginIdAsString();
        return userMapper.selectByUsername(username);
    }

    /**
     * 检查当前用户是否为超级管理员
     */
    public boolean isSuperAdmin() {
        SysUser user = getCurrentUser();
        return user != null && user.getIsSuperAdmin() == 1;
    }

    /**
     * 检查当前用户是否为指定项目组的主管
     */
    public boolean isSupervisor(Long userId, Long groupId) {
        GroupMember member = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getUserId, userId));
        if (member == null) return false;
        PermRole role = permRoleMapper.selectById(member.getRoleId());
        return role != null && "supervisor".equals(role.getName());
    }

    /**
     * 获取用户对指定项目的有效权限
     */
    public Set<String> getEffectivePermissions(Long userId, Long projectId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) return Collections.emptySet();

        // 超级管理员拥有全部权限
        if (user.getIsSuperAdmin() == 1) {
            return getAllPermissionCodes();
        }

        Set<String> permissions = new HashSet<>();

        // 通过项目组成员角色获取权限
        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            GroupMember member = groupMemberMapper.selectOne(
                    new LambdaQueryWrapper<GroupMember>()
                            .eq(GroupMember::getGroupId, project.getGroupId())
                            .eq(GroupMember::getUserId, userId));
            if (member != null) {
                permissions.addAll(getRolePermissions(member.getRoleId()));
                // 合并成员额外权限
                if (member.getExtraPermissions() != null && !member.getExtraPermissions().isEmpty()) {
                    for (String p : member.getExtraPermissions().split(",")) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty()) permissions.add(trimmed);
                    }
                }
            }
        }

        // 跨组项目授权
        List<ProjectAccess> accesses = projectAccessMapper.selectList(
                new LambdaQueryWrapper<ProjectAccess>()
                        .eq(ProjectAccess::getUserId, userId)
                        .eq(ProjectAccess::getProjectId, projectId));
        for (ProjectAccess access : accesses) {
            permissions.add(access.getPermCode());
        }

        return permissions;
    }

    /**
     * 检查用户对指定服务是否有某项权限
     */
    public boolean checkServicePermission(Long userId, Long serviceId, String requiredPerm) {
        Long projectId = externalDataProvider.getProjectIdByServiceId(serviceId);
        if (projectId == null) return false;
        Set<String> perms = getEffectivePermissions(userId, projectId);
        return perms.contains(requiredPerm);
    }

    /**
     * 获取用户可见的所有项目 ID
     */
    public List<Long> getVisibleProjectIds(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) return Collections.emptyList();

        // 超级管理员看到所有项目
        if (user.getIsSuperAdmin() == 1) {
            List<Project> all = projectMapper.selectList(null);
            return all.stream().map(Project::getId).collect(Collectors.toList());
        }

        Set<Long> projectIds = new LinkedHashSet<>();

        // 通过组成员关系获取组内所有项目
        List<GroupMember> memberships = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        for (GroupMember member : memberships) {
            List<Project> projects = projectMapper.selectList(
                    new LambdaQueryWrapper<Project>().eq(Project::getGroupId, member.getGroupId()));
            projects.forEach(p -> projectIds.add(p.getId()));
        }

        // 跨组授权的项目
        List<ProjectAccess> accesses = projectAccessMapper.selectList(
                new LambdaQueryWrapper<ProjectAccess>().eq(ProjectAccess::getUserId, userId));
        accesses.forEach(a -> projectIds.add(a.getProjectId()));

        return new ArrayList<>(projectIds);
    }

    /**
     * 获取用户在所有项目组中的权限并集（用于 /auth/info 返回 projectPermissions）
     */
    public Map<Long, List<String>> getProjectPermissions(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getIsSuperAdmin() == 1) return Collections.emptyMap();

        Map<Long, List<String>> result = new HashMap<>();
        List<Long> visibleIds = getVisibleProjectIds(userId);
        for (Long projectId : visibleIds) {
            Set<String> perms = getEffectivePermissions(userId, projectId);
            if (!perms.isEmpty()) {
                result.put(projectId, new ArrayList<>(perms));
            }
        }
        return result;
    }

    /**
     * 获取用户所属的项目组信息
     */
    public List<Map<String, Object>> getUserGroups(Long userId) {
        List<GroupMember> memberships = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupMember member : memberships) {
            ProjectGroup group = projectGroupMapper.selectById(member.getGroupId());
            if (group == null) continue;
            PermRole role = permRoleMapper.selectById(member.getRoleId());
            Map<String, Object> info = new HashMap<>();
            info.put("id", group.getId());
            info.put("name", group.getName());
            info.put("roleName", role != null ? role.getName() : "unknown");
            info.put("isSupervisor", role != null && "supervisor".equals(role.getName()));
            if (member.getExtraPermissions() != null && !member.getExtraPermissions().isEmpty()) {
                info.put("extraPermissions", List.of(member.getExtraPermissions().split(",")));
            }
            groups.add(info);
        }
        return groups;
    }

    /**
     * 获取角色的权限列表
     */
    public List<String> getRolePermissions(Long roleId) {
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        return rps.stream().map(RolePermission::getPermCode).collect(Collectors.toList());
    }

    /**
     * 获取所有权限码
     */
    public Set<String> getAllPermissionCodes() {
        return Set.of("VIEW", "DEPLOY", "START", "STOP", "UPLOAD", "EDIT_CONFIG", "DELETE",
                "MANAGE_MEMBERS", "MANAGE_PROJECTS");
    }

    /**
     * 通过服务 ID 反查所属的项目 ID
     */
    public Long getProjectIdByServiceId(Long serviceId) {
        return externalDataProvider.getProjectIdByServiceId(serviceId);
    }

    /**
     * 获取用户跨组授权的项目权限
     */
    public Map<Long, List<String>> getCrossProjectPermissions(Long userId) {
        List<ProjectAccess> accesses = projectAccessMapper.selectList(
                new LambdaQueryWrapper<ProjectAccess>().eq(ProjectAccess::getUserId, userId));
        Map<Long, List<String>> result = new HashMap<>();
        for (ProjectAccess access : accesses) {
            result.computeIfAbsent(access.getProjectId(), k -> new ArrayList<>()).add(access.getPermCode());
        }
        return result;
    }
}
