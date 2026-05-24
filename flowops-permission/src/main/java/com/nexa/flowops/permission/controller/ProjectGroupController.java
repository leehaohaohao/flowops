package com.nexa.flowops.permission.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.GroupRequest;
import com.nexa.flowops.permission.entity.ProjectGroup;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.ProjectGroupService;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
public class ProjectGroupController {

    private final ProjectGroupService groupService;
    private final PermissionService permissionService;
    private final SysUserMapper userMapper;

    public ProjectGroupController(ProjectGroupService groupService,
                                   PermissionService permissionService,
                                   SysUserMapper userMapper) {
        this.groupService = groupService;
        this.permissionService = permissionService;
        this.userMapper = userMapper;
    }

    @PostMapping
    @SaCheckRole("super_admin")
    public Result<ProjectGroup> create(@RequestBody GroupRequest req) {
        ProjectGroup group = groupService.create(req.getName(), req.getDescription());
        return Result.ok("创建成功", group);
    }

    @GetMapping
    public Result<List<ProjectGroup>> list() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        if (user.getIsSuperAdmin() == 1) {
            return Result.ok(groupService.listAll());
        }
        return Result.ok(groupService.listByUserId(user.getId()));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable Long id) {
        ProjectGroup group = groupService.getById(id);
        if (group == null) return Result.fail("项目组不存在");

        Map<String, Object> data = new HashMap<>();
        data.put("id", group.getId());
        data.put("name", group.getName());
        data.put("description", group.getDescription());
        data.put("memberCount", groupService.getMemberCount(id));
        data.put("projectCount", groupService.getProjectCount(id));
        return Result.ok(data);
    }

    @PutMapping("/{id}")
    @SaCheckRole("super_admin")
    public Result<Void> update(@PathVariable Long id, @RequestBody GroupRequest req) {
        groupService.update(id, req.getName(), req.getDescription());
        return Result.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    @SaCheckRole("super_admin")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            groupService.delete(id);
            return Result.ok("删除成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }
}
