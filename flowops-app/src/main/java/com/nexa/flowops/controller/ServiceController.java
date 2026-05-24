package com.nexa.flowops.controller;

import com.nexa.flowops.common.RequirePermission;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.dto.CreateServiceRequest;
import com.nexa.flowops.dto.UpdateServiceRequest;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import com.nexa.flowops.service.ServiceMgmtService;
import com.nexa.flowops.common.BusinessException;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @RequirePermission("VIEW")
    @GetMapping("/{id}")
    public Result<DeployService> get(@PathVariable Long id) {
        return Result.ok(serviceMgmtService.getById(id));
    }

    @RequirePermission(value = "EDIT_CONFIG", projectId = "params.projectId")
    @PostMapping("/create")
    public Result<Void> create(@RequestBody CreateServiceRequest req) {
        try {
            serviceMgmtService.createService(req);
            return Result.ok("创建成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @RequirePermission("EDIT_CONFIG")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateServiceRequest req) {
        serviceMgmtService.updateService(id, req);
        return Result.ok("更新成功");
    }

    @RequirePermission("DELETE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        serviceMgmtService.deleteService(id);
        return Result.ok("删除成功");
    }
}
