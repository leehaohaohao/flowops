package com.nexa.flowops.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import com.nexa.flowops.service.MemberService;
import com.nexa.flowops.service.PermissionService;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups/{groupId}/members")
public class MemberController {

    private final MemberService memberService;
    private final PermissionService permissionService;
    private final SysUserMapper userMapper;

    public MemberController(MemberService memberService,
                            PermissionService permissionService,
                            SysUserMapper userMapper) {
        this.memberService = memberService;
        this.permissionService = permissionService;
        this.userMapper = userMapper;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable Long groupId) {
        return Result.ok(memberService.listMembers(groupId));
    }

    @PostMapping
    public Result<Void> add(@PathVariable Long groupId, @RequestBody Map<String, Object> params) {
        Long userId = Long.parseLong(String.valueOf(params.get("userId")));
        Long roleId = Long.parseLong(String.valueOf(params.get("roleId")));
        try {
            memberService.addMember(groupId, userId, roleId);
            return Result.ok("添加成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{userId}")
    public Result<Void> updateRole(@PathVariable Long groupId,
                                   @PathVariable Long userId,
                                   @RequestBody Map<String, Object> params) {
        Long roleId = Long.parseLong(String.valueOf(params.get("roleId")));
        try {
            memberService.updateRole(groupId, userId, roleId);
            return Result.ok("更新成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{userId}")
    public Result<Void> remove(@PathVariable Long groupId, @PathVariable Long userId) {
        memberService.removeMember(groupId, userId);
        return Result.ok("移除成功");
    }
}
