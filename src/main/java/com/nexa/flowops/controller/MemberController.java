package com.nexa.flowops.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.RequireGroupSupervisor;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.dto.AddMemberRequest;
import com.nexa.flowops.dto.UpdateMemberRoleRequest;
import com.nexa.flowops.service.MemberService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups/{groupId}/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @RequireGroupSupervisor("groupId")
    @GetMapping
    public Result<List<Map<String, Object>>> list(@PathVariable Long groupId) {
        return Result.ok(memberService.listMembers(groupId));
    }

    @RequireGroupSupervisor("groupId")
    @PostMapping
    public Result<Void> add(@PathVariable Long groupId, @RequestBody AddMemberRequest req) {
        try {
            memberService.addMember(groupId, req.getUserId(), req.getRoleId());
            return Result.ok("添加成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @RequireGroupSupervisor("groupId")
    @PutMapping("/{userId}")
    public Result<Void> updateRole(@PathVariable Long groupId,
                                   @PathVariable Long userId,
                                   @RequestBody UpdateMemberRoleRequest req) {
        try {
            memberService.updateRole(groupId, userId, req.getRoleId());
            return Result.ok("更新成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @RequireGroupSupervisor("groupId")
    @DeleteMapping("/{userId}")
    public Result<Void> remove(@PathVariable Long groupId, @PathVariable Long userId) {
        memberService.removeMember(groupId, userId);
        return Result.ok("移除成功");
    }
}
