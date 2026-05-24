package com.nexa.flowops.permission.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.permission.dto.GrantAccessRequest;
import com.nexa.flowops.permission.entity.ProjectAccess;
import com.nexa.flowops.permission.mapper.ProjectAccessMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/access")
@SaCheckRole("super_admin")
public class PermissionController {

    private final ProjectAccessMapper projectAccessMapper;

    public PermissionController(ProjectAccessMapper projectAccessMapper) {
        this.projectAccessMapper = projectAccessMapper;
    }

    @PostMapping("/grant")
    public Result<Void> grant(@RequestBody GrantAccessRequest req) {
        for (String permCode : req.getPermCodes()) {
            Long count = projectAccessMapper.selectCount(
                    new LambdaQueryWrapper<ProjectAccess>()
                            .eq(ProjectAccess::getUserId, req.getUserId())
                            .eq(ProjectAccess::getProjectId, req.getProjectId())
                            .eq(ProjectAccess::getPermCode, permCode));
            if (count == 0) {
                ProjectAccess access = new ProjectAccess();
                access.setUserId(req.getUserId());
                access.setProjectId(req.getProjectId());
                access.setPermCode(permCode);
                projectAccessMapper.insert(access);
            }
        }
        return Result.ok("授权成功");
    }

    @GetMapping
    public Result<List<ProjectAccess>> list(@RequestParam Long userId) {
        return Result.ok(projectAccessMapper.selectList(
                new LambdaQueryWrapper<ProjectAccess>().eq(ProjectAccess::getUserId, userId)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        projectAccessMapper.deleteById(id);
        return Result.ok("撤销成功");
    }
}
