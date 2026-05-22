package com.nexa.flowops.controller;

import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.common.Result;
import com.nexa.flowops.entity.PermRole;
import com.nexa.flowops.service.RoleService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/presets")
    public Result<List<PermRole>> presets() {
        return Result.ok(roleService.listPresets());
    }

    @GetMapping
    public Result<List<PermRole>> list(@RequestParam(required = false) Long groupId) {
        if (groupId != null) {
            return Result.ok(roleService.listAvailable(groupId));
        }
        return Result.ok(roleService.listPresets());
    }

    @GetMapping("/{id}/permissions")
    public Result<List<String>> permissions(@PathVariable Long id) {
        return Result.ok(roleService.getPermissions(id));
    }

    @PostMapping
    public Result<PermRole> create(@RequestBody Map<String, Object> params) {
        String name = (String) params.get("name");
        Long groupId = Long.parseLong(String.valueOf(params.get("groupId")));
        String description = (String) params.get("description");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) params.get("permissions");
        PermRole role = roleService.createCustom(name, groupId, description, permissions);
        return Result.ok("创建成功", role);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> params) {
        String name = (String) params.get("name");
        String description = (String) params.get("description");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) params.get("permissions");
        try {
            roleService.updateCustom(id, name, description, permissions);
            return Result.ok("更新成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        try {
            roleService.deleteCustom(id);
            return Result.ok("删除成功");
        } catch (BusinessException e) {
            return Result.fail(e.getMessage());
        }
    }
}
