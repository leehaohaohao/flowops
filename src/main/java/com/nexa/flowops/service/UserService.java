package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.dto.CreateUserRequest;
import com.nexa.flowops.entity.*;
import com.nexa.flowops.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final PermRoleMapper permRoleMapper;

    public UserService(SysUserMapper userMapper,
                       GroupMemberMapper groupMemberMapper,
                       PermRoleMapper permRoleMapper) {
        this.userMapper = userMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.permRoleMapper = permRoleMapper;
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

    @Transactional
    public void createUser(CreateUserRequest req, Long operatorId, boolean isSuperAdmin) {
        if (userMapper.selectByUsername(req.getUsername()) != null) {
            throw new BusinessException("用户名「" + req.getUsername() + "」已存在");
        }

        PermRole role = permRoleMapper.selectById(req.getRoleId());
        if (role == null) {
            throw new BusinessException("角色不存在");
        }

        if (!isSuperAdmin && "supervisor".equals(role.getName())) {
            throw new BusinessException("权限不足：不能分配 supervisor 角色");
        }

        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(req.getPassword());
        user.setRole("user");
        user.setIsSuperAdmin(0);
        userMapper.insert(user);

        // 加入项目组，额外权限直接存到成员表
        GroupMember member = new GroupMember();
        member.setGroupId(req.getGroupId());
        member.setUserId(user.getId());
        member.setRoleId(role.getId());

        List<String> extraPerms = req.getExtraPermissions();
        if (extraPerms != null && !extraPerms.isEmpty()) {
            member.setExtraPermissions(String.join(",", extraPerms));
        }

        groupMemberMapper.insert(member);
    }

    public void deleteUser(Long id) {
        groupMemberMapper.delete(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, id));
        userMapper.deleteById(id);
    }
}
