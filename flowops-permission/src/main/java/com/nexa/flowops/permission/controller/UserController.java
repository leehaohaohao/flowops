package com.nexa.flowops.permission.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.CreateUserRequest;
import com.nexa.flowops.permission.dto.ProjectRoleAssignment;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.ProjectService;
import com.nexa.flowops.permission.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public Result<List<Map<String, Object>>> list() {
        SysUser currentUser = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<SysUser> users;
        if (currentUser.getIsSuperAdmin() == 1) {
            users = userService.list();
        } else {
            // 主管看自己项目的用户（去重）
            List<Long> projectIds = permissionService.getUserProjects(currentUser.getId())
                    .stream().filter(g -> "supervisor".equals(g.get("roleName")))
                    .map(g -> (Long) g.get("id")).toList();
            if (projectIds.isEmpty()) {
                return Result.ok(List.of());
            }
            users = userService.listByProjectIds(projectIds);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser user : users) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", user.getId());
            map.put("username", user.getUsername());
            map.put("isSuperAdmin", user.getIsSuperAdmin() == 1);
            map.put("createTime", user.getCreateTime());
            map.put("projects", permissionService.getUserProjects(user.getId()));
            result.add(map);
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
                if (!permissionService.isSupervisor(operator.getId(), assignment.getProjectId())) {
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
