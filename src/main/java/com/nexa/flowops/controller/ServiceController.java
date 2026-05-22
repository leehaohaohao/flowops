package com.nexa.flowops.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import com.nexa.flowops.service.PermissionService;
import com.nexa.flowops.service.ServiceMgmtService;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceMgmtService serviceMgmtService;
    private final PermissionService permissionService;
    private final SysUserMapper userMapper;

    public ServiceController(ServiceMgmtService serviceMgmtService,
                             PermissionService permissionService,
                             SysUserMapper userMapper) {
        this.serviceMgmtService = serviceMgmtService;
        this.permissionService = permissionService;
        this.userMapper = userMapper;
    }

    @GetMapping("/list")
    public Result<List<DeployService>> list() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<Long> visibleProjectIds = permissionService.getVisibleProjectIds(user.getId());
        return Result.ok(serviceMgmtService.listByProjectIds(visibleProjectIds));
    }

    @GetMapping("/{id}")
    public Result<DeployService> get(@PathVariable Long id) {
        return Result.ok(serviceMgmtService.getById(id));
    }

    @PostMapping("/create")
    public Result<Void> create(@RequestBody Map<String, Object> params) {
        // 检查 EDIT_CONFIG 权限
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        Long projectId = Long.parseLong(String.valueOf(params.get("projectId")));
        if (user.getIsSuperAdmin() != 1) {
            var perms = permissionService.getEffectivePermissions(user.getId(), projectId);
            if (!perms.contains("EDIT_CONFIG")) {
                return Result.fail(403, "权限不足：需要 EDIT_CONFIG 权限");
            }
        }
        try {
            serviceMgmtService.createService(params);
            return Result.ok("创建成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        serviceMgmtService.updateService(id, params);
        return Result.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        serviceMgmtService.deleteService(id);
        return Result.ok("删除成功");
    }
}
