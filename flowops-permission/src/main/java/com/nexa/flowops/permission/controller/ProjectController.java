package com.nexa.flowops.permission.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.RequireProjectSupervisor;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.CreateProjectRequest;
import com.nexa.flowops.permission.dto.UpdateProjectRequest;
import com.nexa.flowops.permission.entity.Project;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.permission.service.ProjectService;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

    @SaCheckRole("super_admin")
    @PostMapping
    public Result<Project> create(@RequestBody CreateProjectRequest req) {
        Project project = projectService.create(req.getName(), req.getDescription());
        return Result.ok("创建成功", project);
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<Project> projects;
        if (user.getIsSuperAdmin() == 1) {
            projects = projectService.listAll();
        } else {
            projects = projectService.listByIds(permissionService.getVisibleProjectIds(user.getId()));
        }
        List<Map<String, Object>> result = projects.stream().map(p -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", p.getId());
            map.put("name", p.getName());
            map.put("description", p.getDescription());
            map.put("isDefault", p.getIsDefault());
            map.put("memberCount", projectService.getMemberCount(p.getId()));
            map.put("serviceCount", projectService.getServiceCount(p.getId()));
            map.put("createTime", p.getCreateTime());
            return map;
        }).toList();
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> getById(@PathVariable Long id) {
        Project project = projectService.getById(id);
        if (project == null) return Result.fail("项目不存在");
        Map<String, Object> data = new HashMap<>();
        data.put("id", project.getId());
        data.put("name", project.getName());
        data.put("description", project.getDescription());
        data.put("isDefault", project.getIsDefault());
        data.put("memberCount", projectService.getMemberCount(id));
        data.put("serviceCount", projectService.getServiceCount(id));
        data.put("createTime", project.getCreateTime());
        return Result.ok(data);
    }

    @RequireProjectSupervisor("id")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateProjectRequest req) {
        projectService.update(id, req.getName(), req.getDescription());
        return Result.ok("更新成功");
    }

    @RequireProjectSupervisor("id")
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
