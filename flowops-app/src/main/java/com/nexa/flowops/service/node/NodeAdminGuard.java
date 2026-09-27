package com.nexa.flowops.service.node;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 节点管理（登记与 SSH 设置）的授权判定：仅超级管理员。
 *
 * <p>与 {@code NodeController} 原有判定完全一致（Sa-Token 登录态 + {@code sys_user.is_super_admin = 1}），
 * 抽成单一实现，避免多处各写一份授权规则。
 * 前端隐藏入口不作为授权依据，所有写操作与测试都必须经过这里。
 */
@Component
@RequiredArgsConstructor
public class NodeAdminGuard {

    private final SysUserMapper userMapper;

    /** 当前登录用户是否为超级管理员；未登录或用户不存在一律返回 false。 */
    public boolean isSuperAdmin() {
        String username;
        try {
            username = StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            return false;
        }
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return false;
        }
        Integer flag = user.getIsSuperAdmin();
        return flag != null && flag == 1;
    }
}
