package com.nexa.flowops.permission.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.RequireProjectSupervisor;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.AddMemberRequest;
import com.nexa.flowops.permission.dto.MemberVO;
import com.nexa.flowops.permission.dto.UpdateMemberRoleRequest;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.MemberService;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
public class MemberController {

    private final MemberService memberService;
    private final SysUserMapper userMapper;

    public MemberController(MemberService memberService, SysUserMapper userMapper) {
        this.memberService = memberService;
        this.userMapper = userMapper;
    }

    @RequireProjectSupervisor("projectId")
    @GetMapping
    public Result<List<MemberVO>> list(@PathVariable Long projectId) {
        return Result.ok(memberService.listMembers(projectId));
    }

    @RequireProjectSupervisor("projectId")
    @PostMapping
    public Result<Void> add(@PathVariable Long projectId, @RequestBody AddMemberRequest req) {
        try {
            memberService.addMember(projectId, req.getUserId(), req.getRoleId());
            return Result.ok("添加成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @RequireProjectSupervisor("projectId")
    @PutMapping("/{userId}")
    public Result<Void> updateRole(@PathVariable Long projectId,
                                   @PathVariable Long userId,
                                   @RequestBody UpdateMemberRoleRequest req) {
        SysUser currentUser = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        if (currentUser != null && currentUser.getId().equals(userId)) {
            return Result.fail("不能修改自己的角色");
        }
        try {
            memberService.updateRole(projectId, userId, req.getRoleId());
            return Result.ok("更新成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @RequireProjectSupervisor("projectId")
    @DeleteMapping("/{userId}")
    public Result<Void> remove(@PathVariable Long projectId, @PathVariable Long userId) {
        SysUser currentUser = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        if (currentUser != null && currentUser.getId().equals(userId)) {
            return Result.fail("不能移除自己");
        }
        memberService.removeMember(projectId, userId);
        return Result.ok("移除成功");
    }
}
