package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.common.BusinessException;
import com.nexa.flowops.entity.PermRole;
import com.nexa.flowops.entity.RolePermission;
import com.nexa.flowops.mapper.PermRoleMapper;
import com.nexa.flowops.mapper.RolePermissionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RoleService {

    private final PermRoleMapper permRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public RoleService(PermRoleMapper permRoleMapper, RolePermissionMapper rolePermissionMapper) {
        this.permRoleMapper = permRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    public List<PermRole> listPresets() {
        return permRoleMapper.selectList(
                new LambdaQueryWrapper<PermRole>().eq(PermRole::getIsPreset, 1));
    }

    public List<PermRole> listAvailable(Long groupId) {
        // 预设角色 + 该组的自定义角色
        return permRoleMapper.selectList(
                new LambdaQueryWrapper<PermRole>()
                        .eq(PermRole::getIsPreset, 1)
                        .or(w -> w.eq(PermRole::getGroupId, groupId)));
    }

    public PermRole getById(Long id) {
        return permRoleMapper.selectById(id);
    }

    @Transactional
    public PermRole createCustom(String name, Long groupId, String description, List<String> permissions) {
        PermRole role = new PermRole();
        role.setName(name);
        role.setIsPreset(0);
        role.setGroupId(groupId);
        role.setDescription(description);
        permRoleMapper.insert(role);

        for (String perm : permissions) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(role.getId());
            rp.setPermCode(perm);
            rolePermissionMapper.insert(rp);
        }
        return role;
    }

    @Transactional
    public void updateCustom(Long id, String name, String description, List<String> permissions) {
        PermRole role = permRoleMapper.selectById(id);
        if (role == null) throw new BusinessException("角色不存在");
        if (role.getIsPreset() == 1) throw new BusinessException("不能修改预设角色");

        if (name != null) role.setName(name);
        if (description != null) role.setDescription(description);
        permRoleMapper.updateById(role);

        // 重建权限映射
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        for (String perm : permissions) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(id);
            rp.setPermCode(perm);
            rolePermissionMapper.insert(rp);
        }
    }

    public void deleteCustom(Long id) {
        PermRole role = permRoleMapper.selectById(id);
        if (role == null) throw new BusinessException("角色不存在");
        if (role.getIsPreset() == 1) throw new BusinessException("不能删除预设角色");

        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        permRoleMapper.deleteById(id);
    }

    public List<String> getPermissions(Long roleId) {
        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        return rps.stream().map(RolePermission::getPermCode).toList();
    }
}
