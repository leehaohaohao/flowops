package com.nexa.flowops.permission.controller;

import com.nexa.flowops.common.base.BusinessException;
import com.nexa.flowops.common.anno.RequirePermission;
import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.permission.dto.CreateProjectRequest;
import com.nexa.flowops.permission.dto.ProjectDetailVO;
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
    public Result<List<ProjectDetailVO>> list() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<Project> projects;
        if (user.getIsSuperAdmin() == 1) {
            projects = projectService.listAll();
        } else {
            projects = projectService.listByIds(permissionService.getVisibleProjectIds(user.getId()));
        }
        List<ProjectDetailVO> result = projects.stream().map(p -> {
            ProjectDetailVO vo = new ProjectDetailVO();
            vo.setId(p.getId());
            vo.setName(p.getName());
            vo.setDescription(p.getDescription());
            vo.setIsDefault(p.getIsDefault());
            vo.setMemberCount(projectService.getMemberCount(p.getId()));
            vo.setServiceCount(projectService.getServiceCount(p.getId()));
            vo.setRunningCount(projectService.getRunningCount(p.getId()));
            vo.setCreateTime(p.getCreateTime());
            return vo;
        }).toList();
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<ProjectDetailVO> getById(@PathVariable Long id) {
        Project project = projectService.getById(id);
        if (project == null) return Result.fail("项目不存在");
        ProjectDetailVO vo = new ProjectDetailVO();
        vo.setId(project.getId());
        vo.setName(project.getName());
        vo.setDescription(project.getDescription());
        vo.setIsDefault(project.getIsDefault());
        vo.setMemberCount(projectService.getMemberCount(id));
        vo.setServiceCount(projectService.getServiceCount(id));
        vo.setCreateTime(project.getCreateTime());
        return Result.ok(vo);
    }

    @RequirePermission(value = "MANAGE_PROJECTS", projectId = "id")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateProjectRequest req) {
        projectService.update(id, req.getName(), req.getDescription());
        return Result.ok("更新成功");
    }

    @RequirePermission(value = "MANAGE_PROJECTS", projectId = "id")
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
