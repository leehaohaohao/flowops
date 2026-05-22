package com.nexa.flowops.config;

import cn.dev33.satoken.stp.StpInterface;
import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import com.nexa.flowops.service.PermissionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class StpInterfaceImpl implements StpInterface {

    private final SysUserMapper userMapper;
    private final PermissionService permissionService;

    public StpInterfaceImpl(SysUserMapper userMapper, PermissionService permissionService) {
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        SysUser user = userMapper.selectByUsername(loginId.toString());
        if (user == null) return Collections.emptyList();

        if (user.getIsSuperAdmin() == 1) {
            return new ArrayList<>(permissionService.getAllPermissionCodes());
        }

        // 返回用户在所有项目中的权限并集
        List<Long> visibleProjectIds = permissionService.getVisibleProjectIds(user.getId());
        List<String> allPerms = new ArrayList<>();
        for (Long projectId : visibleProjectIds) {
            allPerms.addAll(permissionService.getEffectivePermissions(user.getId(), projectId));
        }
        return allPerms.stream().distinct().toList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        SysUser user = userMapper.selectByUsername(loginId.toString());
        if (user == null) return Collections.emptyList();

        List<String> roles = new ArrayList<>();
        if (user.getIsSuperAdmin() == 1) {
            roles.add("super_admin");
        }
        roles.add("user");
        return roles;
    }
}
