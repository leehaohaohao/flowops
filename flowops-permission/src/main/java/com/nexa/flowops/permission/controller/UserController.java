package com.nexa.flowops.permission.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.RequireGroupSupervisor;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.CreateUserRequest;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    public UserController(UserService userService, SysUserMapper userMapper,
                          PermissionService permissionService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    @GetMapping("/list")
    public Result<List<SysUser>> list() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        if (user.getIsSuperAdmin() == 1) {
            return Result.ok(userService.list());
        }
        // 主管看自己组内的用户（去重）
        List<Long> groupIds = permissionService.getUserGroups(user.getId())
                .stream().filter(g -> (boolean) g.get("isSupervisor"))
                .map(g -> (Long) g.get("id")).toList();
        if (groupIds.isEmpty()) {
            return Result.ok(List.of());
        }
        return Result.ok(userService.listByGroupIds(groupIds));
    }

    @PostMapping("/create")
    public Result<Void> create(@RequestBody CreateUserRequest req) {
        SysUser operator = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        boolean isSuperAdmin = operator.getIsSuperAdmin() == 1;

        if (!isSuperAdmin) {
            // 主管只能创建到自己的组，且不能分配 supervisor 角色
            if (!permissionService.isSupervisor(operator.getId(), req.getGroupId())) {
                return Result.fail(403, "只能创建用户到自己管理的项目组");
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
        userService.deleteUser(id);
        return Result.ok("删除成功");
    }
}
