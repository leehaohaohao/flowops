package com.nexa.flowops.permission.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.AssignableVO;
import com.nexa.flowops.permission.dto.CreateUserRequest;
import com.nexa.flowops.permission.dto.ProjectRoleAssignment;
import com.nexa.flowops.permission.dto.UpdateUserRequest;
import com.nexa.flowops.permission.dto.UserVO;
import com.nexa.flowops.permission.entity.PermRole;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.ProjectService;
import com.nexa.flowops.permission.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;
    private final ProjectService projectService;

    public UserController(UserService userService, SysUserMapper userMapper,
                          PermissionService permissionService,
                          ProjectService projectService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.permissionService = permissionService;
        this.projectService = projectService;
    }

    @GetMapping("/list")
    public Result<List<UserVO>> list() {
        SysUser currentUser = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<SysUser> users;
        if (currentUser.getIsSuperAdmin() == 1) {
            users = userService.list();
        } else {
            List<Long> projectIds = permissionService.getVisibleProjectIds(currentUser.getId())
                    .stream()
                    .filter(pid -> permissionService.getEffectivePermissions(currentUser.getId(), pid).contains("MANAGE_MEMBERS"))
                    .toList();
            if (projectIds.isEmpty()) {
                return Result.ok(List.of());
            }
            users = userService.listByProjectIds(projectIds);
        }

        List<UserVO> result = new ArrayList<>();
        for (SysUser user : users) {
            UserVO vo = new UserVO();
            vo.setId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setSuperAdmin(user.getIsSuperAdmin() == 1);
            vo.setCreateTime(user.getCreateTime());
            vo.setProjects(permissionService.getUserProjects(user.getId()));
            result.add(vo);
        }
        return Result.ok(result);
    }

    @PostMapping("/create")
    public Result<Void> create(@RequestBody CreateUserRequest req) {
        SysUser operator = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        boolean isSuperAdmin = operator.getIsSuperAdmin() == 1;

        // 非超管需要校验每个项目的 supervisor 权限
        if (!isSuperAdmin && req.getProjects() != null) {
            for (ProjectRoleAssignment assignment : req.getProjects()) {
                if (!permissionService.getEffectivePermissions(operator.getId(), assignment.getProjectId()).contains("MANAGE_MEMBERS")) {
                    return Result.fail(403, "只能创建用户到自己管理的项目（projectId=" + assignment.getProjectId() + "）");
                }
            }
        }

        try {
            userService.createUser(req, operator.getId(), isSuperAdmin);
            return Result.ok("创建成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        SysUser operator = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        boolean isSuperAdmin = operator.getIsSuperAdmin() == 1;

        // 非超管需要校验每个项目的 supervisor 权限
        if (!isSuperAdmin && req.getProjects() != null) {
            for (ProjectRoleAssignment assignment : req.getProjects()) {
                if (!permissionService.getEffectivePermissions(operator.getId(), assignment.getProjectId()).contains("MANAGE_MEMBERS")) {
                    return Result.fail(403, "只能管理自己负责的项目（projectId=" + assignment.getProjectId() + "）");
                }
            }
        }

        try {
            userService.updateUser(id, req.getProjects(), isSuperAdmin);
            return Result.ok("更新成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/assignable")
    public Result<AssignableVO> assignable(@RequestParam Long projectId) {
        SysUser operator = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        boolean isSuperAdmin = operator.getIsSuperAdmin() == 1;

        // 非超管需要有该项目的 MANAGE_MEMBERS 权限
        if (!isSuperAdmin
                && !permissionService.getEffectivePermissions(operator.getId(), projectId).contains("MANAGE_MEMBERS")) {
            return Result.fail(403, "权限不足");
        }

        Set<String> allPerms = permissionService.getAllPermissionCodes();
        List<PermRole> allRoles = userService.getAllRoles();

        AssignableVO vo = new AssignableVO();

        if (isSuperAdmin) {
            // 超管可分配所有角色和权限
            vo.setRoles(allRoles.stream().map(this::toRoleVO).toList());
            vo.setPermissions(new ArrayList<>(allPerms));
        } else {
            // 非超管不能分配 supervisor 角色和管理类权限
            vo.setRoles(allRoles.stream()
                    .filter(r -> !"supervisor".equals(r.getName()))
                    .map(this::toRoleVO).toList());
            vo.setPermissions(allPerms.stream()
                    .filter(p -> !Set.of("MANAGE_MEMBERS", "MANAGE_PROJECTS").contains(p))
                    .toList());
        }

        return Result.ok(vo);
    }

    private AssignableVO.RoleVO toRoleVO(PermRole role) {
        AssignableVO.RoleVO vo = new AssignableVO.RoleVO();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setDescription(role.getDescription());
        vo.setPermissions(permissionService.getRolePermissions(role.getId()));
        return vo;
    }

    @DeleteMapping("/{id}")
    @SaCheckRole("super_admin")
    public Result<Void> delete(@PathVariable Long id) {
        SysUser operator = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        if (operator.getId().equals(id)) {
            return Result.fail("不能删除自己");
        }
        userService.deleteUser(id);
        return Result.ok("删除成功");
    }
}
