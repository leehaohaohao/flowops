package com.nexa.flowops.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.PasswordUtil;
import com.nexa.flowops.permission.dto.CreateUserRequest;
import com.nexa.flowops.permission.dto.ProjectRoleAssignment;
import com.nexa.flowops.permission.entity.*;
import com.nexa.flowops.permission.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final PermRoleMapper permRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final ProjectService projectService;

    public UserService(SysUserMapper userMapper,
                       GroupMemberMapper groupMemberMapper,
                       PermRoleMapper permRoleMapper,
                       RolePermissionMapper rolePermissionMapper,
                       ProjectService projectService) {
        this.userMapper = userMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.permRoleMapper = permRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.projectService = projectService;
    }

    public List<SysUser> list() {
        return userMapper.selectList(null);
    }

    public List<SysUser> listByProjectIds(List<Long> projectIds) {
        if (projectIds.isEmpty()) return List.of();
        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().in(GroupMember::getProjectId, projectIds));
        List<Long> userIds = members.stream().map(GroupMember::getUserId).distinct().toList();
        if (userIds.isEmpty()) return List.of();
        return userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().in(SysUser::getId, userIds));
    }

    @Transactional
    public void createUser(CreateUserRequest req, Long operatorId, boolean isSuperAdmin) {
        if (userMapper.selectByUsername(req.getUsername()) != null) {
            throw new BusinessException("用户名「" + req.getUsername() + "」已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(PasswordUtil.encode(req.getPassword()));
        user.setIsSuperAdmin(0);
        userMapper.insert(user);

        List<ProjectRoleAssignment> assignments = req.getProjects();
        if (assignments == null || assignments.isEmpty()) {
            // 未指定项目，归入默认项目（viewer 角色）
            Project defaultProject = projectService.getDefaultProject();
            if (defaultProject == null) {
                throw new BusinessException("系统未配置默认项目，请联系管理员");
            }
            GroupMember member = new GroupMember();
            member.setProjectId(defaultProject.getId());
            member.setUserId(user.getId());
            member.setRoleId(1L); // viewer
            groupMemberMapper.insert(member);
            return;
        }

        for (ProjectRoleAssignment assignment : assignments) {
            PermRole role = permRoleMapper.selectById(assignment.getRoleId());
            if (role == null) {
                throw new BusinessException("角色不存在：roleId=" + assignment.getRoleId());
            }
            if (!isSuperAdmin && "supervisor".equals(role.getName())) {
                throw new BusinessException("权限不足：不能分配 supervisor 角色");
            }

            GroupMember member = new GroupMember();
            member.setProjectId(assignment.getProjectId());
            member.setUserId(user.getId());
            member.setRoleId(assignment.getRoleId());

            List<String> extraPerms = assignment.getExtraPermissions();
            if (extraPerms != null && !extraPerms.isEmpty()) {
                member.setExtraPermissions(String.join(",", extraPerms));
            }

            groupMemberMapper.insert(member);
        }
    }

    public void deleteUser(Long id) {
        groupMemberMapper.delete(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, id));
        userMapper.deleteById(id);
    }

    @Transactional
    public void updateUser(Long userId, List<ProjectRoleAssignment> assignments,
                           boolean isSuperAdmin) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException("用户不存在：id=" + userId);
        }

        // Step 1: 合并重复 projectId
        Map<Long, ProjectRoleAssignment> merged = mergeAssignments(assignments, isSuperAdmin);

        // Step 2: 获取现有 GroupMember，按 projectId 建 Map
        List<GroupMember> existing = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        Map<Long, GroupMember> existingMap = existing.stream()
                .collect(Collectors.toMap(GroupMember::getProjectId, m -> m));

        Set<Long> newProjectIds = merged.keySet();

        // Step 3: 新增 + 更新
        for (Map.Entry<Long, ProjectRoleAssignment> entry : merged.entrySet()) {
            Long projectId = entry.getKey();
            ProjectRoleAssignment assignment = entry.getValue();

            PermRole role = permRoleMapper.selectById(assignment.getRoleId());
            if (role == null) {
                throw new BusinessException("角色不存在：roleId=" + assignment.getRoleId());
            }
            if (!isSuperAdmin && "supervisor".equals(role.getName())) {
                throw new BusinessException("权限不足：不能分配 supervisor 角色");
            }

            String extraPermsStr = null;
            List<String> extraPerms = assignment.getExtraPermissions();
            if (extraPerms != null && !extraPerms.isEmpty()) {
                extraPermsStr = String.join(",", extraPerms);
            }

            GroupMember existingMember = existingMap.get(projectId);
            if (existingMember == null) {
                // 新增
                GroupMember member = new GroupMember();
                member.setProjectId(projectId);
                member.setUserId(userId);
                member.setRoleId(assignment.getRoleId());
                member.setExtraPermissions(extraPermsStr);
                groupMemberMapper.insert(member);
            } else {
                // 更新（roleId 或 extraPermissions 有变化）
                boolean changed = !existingMember.getRoleId().equals(assignment.getRoleId())
                        || !Objects.equals(
                                Optional.ofNullable(existingMember.getExtraPermissions()).orElse(""),
                                Optional.ofNullable(extraPermsStr).orElse(""));
                if (changed) {
                    existingMember.setRoleId(assignment.getRoleId());
                    existingMember.setExtraPermissions(extraPermsStr);
                    groupMemberMapper.updateById(existingMember);
                }
            }
        }

        // Step 4: 删除不在新列表中的
        for (Map.Entry<Long, GroupMember> entry : existingMap.entrySet()) {
            if (!newProjectIds.contains(entry.getKey())) {
                groupMemberMapper.deleteById(entry.getValue().getId());
            }
        }
    }

    /**
     * 合并重复 projectId：选择权限最多的角色，其余权限作为扩展权限并入
     */
    private Map<Long, ProjectRoleAssignment> mergeAssignments(
            List<ProjectRoleAssignment> assignments, boolean isSuperAdmin) {
        Map<Long, List<ProjectRoleAssignment>> grouped = assignments.stream()
                .filter(a -> a.getProjectId() != null && a.getRoleId() != null)
                .collect(Collectors.groupingBy(ProjectRoleAssignment::getProjectId));

        Map<Long, ProjectRoleAssignment> result = new HashMap<>();
        for (Map.Entry<Long, List<ProjectRoleAssignment>> entry : grouped.entrySet()) {
            List<ProjectRoleAssignment> group = entry.getValue();
            if (group.size() == 1) {
                result.put(entry.getKey(), group.get(0));
                continue;
            }

            // 选择权限最多的角色
            ProjectRoleAssignment best = group.stream()
                    .max(Comparator.comparingInt(a -> getRolePermCount(a.getRoleId())))
                    .orElse(group.get(0));

            // 收集所有额外权限，排除已选角色已有的权限
            Set<String> rolePermCodes = getRolePermCodes(best.getRoleId());
            Set<String> allExtra = group.stream()
                    .filter(a -> a.getExtraPermissions() != null)
                    .flatMap(a -> a.getExtraPermissions().stream())
                    .filter(p -> !rolePermCodes.contains(p))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            ProjectRoleAssignment merged = new ProjectRoleAssignment();
            merged.setProjectId(entry.getKey());
            merged.setRoleId(best.getRoleId());
            merged.setExtraPermissions(allExtra.isEmpty() ? null : new ArrayList<>(allExtra));
            result.put(entry.getKey(), merged);
        }
        return result;
    }

    private int getRolePermCount(Long roleId) {
        return rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId)).intValue();
    }

    private Set<String> getRolePermCodes(Long roleId) {
        List<RolePermission> perms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getRoleId, roleId));
        return perms.stream().map(RolePermission::getPermCode).collect(Collectors.toSet());
    }
}
