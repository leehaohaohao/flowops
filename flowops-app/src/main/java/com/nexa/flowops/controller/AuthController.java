package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.UserInfoVO;
import com.nexa.flowops.dto.LoginRequest;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.service.AuthService;
import com.nexa.flowops.permission.service.PermissionService;
import org.springframework.web.bind.annotation.*;

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
    public Result<UserInfoVO> info() {
        String username = StpUtil.getLoginIdAsString();
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return Result.fail(401, "用户不存在");
        }

        UserInfoVO vo = new UserInfoVO();
        vo.setUsername(user.getUsername());
        vo.setSuperAdmin(user.getIsSuperAdmin() == 1);
        vo.setProjects(permissionService.getUserProjects(user.getId()));

        if (user.getIsSuperAdmin() != 1) {
            vo.setProjectPermissions(permissionService.getProjectPermissions(user.getId()));
        }

        return Result.ok(vo);
    }
}
