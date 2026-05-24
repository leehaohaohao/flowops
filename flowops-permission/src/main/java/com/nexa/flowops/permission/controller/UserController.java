package com.nexa.flowops.permission.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.CreateUserRequest;
import com.nexa.flowops.permission.dto.ProjectRoleAssignment;
import com.nexa.flowops.permission.dto.UpdateUserRequest;
import com.nexa.flowops.permission.dto.UserVO;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.ProjectService;
import com.nexa.flowops.permission.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
            List<Long> projectIds = permissionService.getUserProjects(currentUser.getId())
                    .stream().filter(g -> "supervisor".equals(g.getRoleName()))
                    .map(g -> g.getId()).toList();
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
