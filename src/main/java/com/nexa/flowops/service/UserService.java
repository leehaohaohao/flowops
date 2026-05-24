package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.dto.CreateUserRequest;
import com.nexa.flowops.entity.*;
import com.nexa.flowops.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final PermRoleMapper permRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public UserService(SysUserMapper userMapper,
                       GroupMemberMapper groupMemberMapper,
                       PermRoleMapper permRoleMapper,
                       RolePermissionMapper rolePermissionMapper) {
        this.userMapper = userMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.permRoleMapper = permRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    public List<SysUser> list() {
        return userMapper.selectList(null);
    }

    public List<SysUser> listByGroupIds(List<Long> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().in(GroupMember::getGroupId, groupIds));
        List<Long> userIds = members.stream().map(GroupMember::getUserId).distinct().toList();
        if (userIds.isEmpty()) return List.of();
        return userMapper.selectList(
                new LambdaQueryWrapper<SysUser>().in(SysUser::getId, userIds));
    }

    /**
     * 创建用户并加入项目组。
     * 如果 extraPermissions 非空，自动创建自定义角色（角色权限 ∪ 额外权限）。
     */
    @Transactional
    public void createUser(CreateUserRequest req, Long operatorId, boolean isSuperAdmin) {
        // 检查用户名是否已存在
        if (userMapper.selectByUsername(req.getUsername()) != null) {
            throw new BusinessException("用户名「" + req.getUsername() + "」已存在");
        }

        // 校验角色
        PermRole role = permRoleMapper.selectById(req.getRoleId());
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        // 非超级管理员不能分配 supervisor 角色
        if (!isSuperAdmin && "supervisor".equals(role.getName())) {
            throw new BusinessException("权限不足：不能分配 supervisor 角色");
        }

        // 创建用户
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(req.getPassword());
        user.setRole("user");
        user.setIsSuperAdmin(0);
        userMapper.insert(user);

        // 决定最终使用的角色 ID
        Long finalRoleId = role.getId();

        // 如果有额外权限，创建自定义角色（角色权限 ∪ 额外权限）
        List<String> extraPerms = req.getExtraPermissions();
        if (extraPerms != null && !extraPerms.isEmpty()) {
            // 获取原角色的权限
            List<RolePermission> existing = rolePermissionMapper.selectList(
                    new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, role.getId()));
            Set<String> allPerms = new LinkedHashSet<>();
            existing.forEach(rp -> allPerms.add(rp.getPermCode()));
            allPerms.addAll(extraPerms);

            // 创建自定义角色
            PermRole customRole = new PermRole();
            customRole.setName(role.getName() + "+");
            customRole.setIsPreset(0);
            customRole.setGroupId(req.getGroupId());
            customRole.setDescription("基于「" + role.getName() + "」扩展权限");
            permRoleMapper.insert(customRole);

            for (String perm : allPerms) {
                RolePermission rp = new RolePermission();
                rp.setRoleId(customRole.getId());
                rp.setPermCode(perm);
                rolePermissionMapper.insert(rp);
            }
            finalRoleId = customRole.getId();
        }

        // 加入项目组
        GroupMember member = new GroupMember();
        member.setGroupId(req.getGroupId());
        member.setUserId(user.getId());
        member.setRoleId(finalRoleId);
        groupMemberMapper.insert(member);
    }

    public void deleteUser(Long id) {
        // 删除组成员关系
        groupMemberMapper.delete(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, id));
        userMapper.deleteById(id);
    }
}
