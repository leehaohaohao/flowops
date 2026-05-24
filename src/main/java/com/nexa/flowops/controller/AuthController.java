package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.dto.LoginRequest;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import com.nexa.flowops.service.AuthService;
import com.nexa.flowops.service.PermissionService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    public AuthController(AuthService authService, SysUserMapper userMapper,
                          PermissionService permissionService) {
        this.authService = authService;
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginRequest req) {
        if (authService.login(req.getUsername(), req.getPassword())) {
            StpUtil.login(req.getUsername());
            return Result.ok("登录成功", StpUtil.getTokenValue());
        }
        return Result.fail(401, "用户名或密码错误");
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.ok("退出成功");
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> info() {
        String username = StpUtil.getLoginIdAsString();
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return Result.fail(401, "用户不存在");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("username", user.getUsername());
        data.put("isSuperAdmin", user.getIsSuperAdmin() == 1);

        List<Map<String, Object>> groups = permissionService.getUserGroups(user.getId());
        data.put("groups", groups);

        if (user.getIsSuperAdmin() != 1) {
            data.put("projectPermissions", permissionService.getProjectPermissions(user.getId()));
        }

        return Result.ok(data);
    }
}
