package com.nexa.flowops.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.permission.entity.GroupMember;
import com.nexa.flowops.permission.entity.PermRole;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.GroupMemberMapper;
import com.nexa.flowops.permission.mapper.PermRoleMapper;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MemberService {

    private final GroupMemberMapper groupMemberMapper;
    private final SysUserMapper userMapper;
    private final PermRoleMapper permRoleMapper;

    public MemberService(GroupMemberMapper groupMemberMapper,
                         SysUserMapper userMapper,
                         PermRoleMapper permRoleMapper) {
        this.groupMemberMapper = groupMemberMapper;
        this.userMapper = userMapper;
        this.permRoleMapper = permRoleMapper;
    }

    public List<Map<String, Object>> listMembers(Long projectId) {
        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getProjectId, projectId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupMember member : members) {
            SysUser user = userMapper.selectById(member.getUserId());
            PermRole role = permRoleMapper.selectById(member.getRoleId());
            if (user == null) continue;
            Map<String, Object> info = new HashMap<>();
            info.put("userId", user.getId());
            info.put("username", user.getUsername());
            info.put("roleId", member.getRoleId());
            info.put("roleName", role != null ? role.getName() : "unknown");
            info.put("isSupervisor", role != null && "supervisor".equals(role.getName()));
            result.add(info);
        }
        return result;
    }

    public void addMember(Long projectId, Long userId, Long roleId) {
        GroupMember existing = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getProjectId, projectId)
                        .eq(GroupMember::getUserId, userId));
        if (existing != null) {
            throw new BusinessException("该用户已是项目成员");
        }
        GroupMember member = new GroupMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRoleId(roleId);
        groupMemberMapper.insert(member);
    }

    public void updateRole(Long projectId, Long userId, Long roleId) {
        GroupMember member = groupMemberMapper.selectOne(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getProjectId, projectId)
                        .eq(GroupMember::getUserId, userId));
        if (member == null) throw new BusinessException("该用户不是项目成员");
        member.setRoleId(roleId);
        groupMemberMapper.updateById(member);
    }

    public void removeMember(Long projectId, Long userId) {
        groupMemberMapper.delete(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getProjectId, projectId)
                        .eq(GroupMember::getUserId, userId));
    }

    public List<Long> getUserProjectIds(Long userId) {
        List<GroupMember> memberships = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>().eq(GroupMember::getUserId, userId));
        return memberships.stream().map(GroupMember::getProjectId).toList();
    }
}
