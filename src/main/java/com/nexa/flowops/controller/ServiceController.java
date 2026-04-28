package com.nexa.flowops.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nexa.flowops.service.ServiceMgmtService;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;

import java.util.Map;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

    private final ServiceMgmtService serviceMgmtService;

    public ServiceController(ServiceMgmtService serviceMgmtService) {
        this.serviceMgmtService = serviceMgmtService;
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return Map.of("code", 200, "data", serviceMgmtService.list());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return Map.of("code", 200, "data", serviceMgmtService.getById(id));
    }

    @PostMapping("/create")
    @SaCheckRole("admin")
    public Map<String, Object> create(@RequestBody Map<String, Object> params) {
        serviceMgmtService.createService(params);
        return Map.of("code", 200, "msg", "创建成功");
    }

    @PutMapping("/{id}")
    @SaCheckRole("admin")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        serviceMgmtService.updateService(id, params);
        return Map.of("code", 200, "msg", "更新成功");
    }

    @DeleteMapping("/{id}")
    @SaCheckRole("admin")
    public Map<String, Object> delete(@PathVariable Long id) {
        serviceMgmtService.deleteService(id);
        return Map.of("code", 200, "msg", "删除成功");
    }
}
