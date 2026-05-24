package com.nexa.flowops.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.dto.CreateUserRequest;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@SaCheckRole("super_admin")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/list")
    public Result<List<SysUser>> list() {
        return Result.ok(userService.list());
    }

    @PostMapping("/create")
    public Result<Void> create(@RequestBody CreateUserRequest req) {
        userService.createUser(req.getUsername(), req.getPassword(), req.getRole());
        return Result.ok("创建成功");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.ok("删除成功");
    }
}
