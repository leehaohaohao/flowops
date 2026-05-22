package com.nexa.flowops.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.Project;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import com.nexa.flowops.service.PermissionService;
import com.nexa.flowops.service.ProjectService;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final PermissionService permissionService;
    private final SysUserMapper userMapper;

    public ProjectController(ProjectService projectService,
                             PermissionService permissionService,
                             SysUserMapper userMapper) {
        this.projectService = projectService;
        this.permissionService = permissionService;
        this.userMapper = userMapper;
    }

    @PostMapping
    public Result<Project> create(@RequestBody Map<String, Object> params) {
        Long groupId = Long.parseLong(String.valueOf(params.get("groupId")));
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());

        // 检查权限：超级管理员或该项目组的主管
        if (user.getIsSuperAdmin() != 1 && !permissionService.isSupervisor(user.getId(), groupId)) {
            return Result.fail(403, "权限不足：需要超级管理员或项目组主管权限");
        }

        String name = (String) params.get("name");
        String description = (String) params.get("description");
        Project project = projectService.create(groupId, name, description);
        return Result.ok("创建成功", project);
    }

    @GetMapping
    public Result<List<Project>> list(@RequestParam(required = false) Long groupId) {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        if (user.getIsSuperAdmin() == 1) {
            if (groupId != null) {
                return Result.ok(projectService.listByGroupId(groupId));
            }
            return Result.ok(projectService.listByIds(permissionService.getVisibleProjectIds(user.getId())));
        }
        List<Long> visibleIds = permissionService.getVisibleProjectIds(user.getId());
        return Result.ok(projectService.listByIds(visibleIds));
    }

    @GetMapping("/{id}")
    public Result<Project> getById(@PathVariable Long id) {
        return Result.ok(projectService.getById(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, String> params) {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        Project project = projectService.getById(id);
        if (project == null) return Result.fail("项目不存在");

        if (user.getIsSuperAdmin() != 1 && !permissionService.isSupervisor(user.getId(), project.getGroupId())) {
            return Result.fail(403, "权限不足");
        }

        projectService.update(id, params.get("name"), params.get("description"));
        return Result.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        Project project = projectService.getById(id);
        if (project == null) return Result.fail("项目不存在");

        if (user.getIsSuperAdmin() != 1 && !permissionService.isSupervisor(user.getId(), project.getGroupId())) {
            return Result.fail(403, "权限不足");
        }

        try {
            projectService.delete(id);
            return Result.ok("删除成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }
}
