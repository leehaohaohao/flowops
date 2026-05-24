package com.nexa.flowops.permission.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.RequireGroupSupervisor;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.CreateProjectRequest;
import com.nexa.flowops.permission.dto.UpdateProjectRequest;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.ProjectService;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @RequireGroupSupervisor("params.groupId")
    @PostMapping
    public Result<Project> create(@RequestBody CreateProjectRequest req) {
        Project project = projectService.create(req.getGroupId(), req.getName(), req.getDescription());
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

    @RequireGroupSupervisor("project:id")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateProjectRequest req) {
        projectService.update(id, req.getName(), req.getDescription());
        return Result.ok("更新成功");
    }

    @RequireGroupSupervisor("project:id")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            projectService.delete(id);
            return Result.ok("删除成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }
}
