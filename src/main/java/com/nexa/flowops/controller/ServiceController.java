package com.nexa.flowops.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.service.ServiceMgmtService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceMgmtService serviceMgmtService;

    public ServiceController(ServiceMgmtService serviceMgmtService) {
        this.serviceMgmtService = serviceMgmtService;
    }

    @GetMapping("/list")
    public Result<List<DeployService>> list() {
        return Result.ok(serviceMgmtService.list());
    }

    @GetMapping("/{id}")
    public Result<DeployService> get(@PathVariable Long id) {
        return Result.ok(serviceMgmtService.getById(id));
    }

    @PostMapping("/create")
    @SaCheckRole("admin")
    public Result<Void> create(@RequestBody Map<String, Object> params) {
        try {
            serviceMgmtService.createService(params);
            return Result.ok("创建成功");
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @SaCheckRole("admin")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        serviceMgmtService.updateService(id, params);
        return Result.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    @SaCheckRole("admin")
    public Result<Void> delete(@PathVariable Long id) {
        serviceMgmtService.deleteService(id);
        return Result.ok("删除成功");
    }
}
