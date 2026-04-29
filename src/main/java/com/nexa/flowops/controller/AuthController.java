package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");
        if (authService.login(username, password)) {
            StpUtil.login(username);
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
    public Result<Map<String, String>> info() {
        Map<String, String> userInfo = Map.of(
                "username", StpUtil.getLoginIdAsString(),
                "role", StpUtil.hasRole("admin") ? "admin" : "user"
        );
        return Result.ok(userInfo);
    }
}
