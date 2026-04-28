package com.nexa.flowops.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@SaCheckRole("admin")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", userService.list());
    }

    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody Map<String, String> params) {
        userService.createUser(params.get("username"), params.get("password"), params.get("role"));
        return Map.of("code", 200, "msg", "创建成功");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Map.of("code", 200, "msg", "删除成功");
    }
}