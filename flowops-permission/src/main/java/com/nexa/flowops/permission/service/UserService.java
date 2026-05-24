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

import java.util.List;

@Service
public class UserService {

    private final SysUserMapper userMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final PermRoleMapper permRoleMapper;
    private final ProjectService projectService;

    public UserService(SysUserMapper userMapper,
                       GroupMemberMapper groupMemberMapper,
                       PermRoleMapper permRoleMapper,
                       ProjectService projectService) {
        this.userMapper = userMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.permRoleMapper = permRoleMapper;
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
}
