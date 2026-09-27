package com.nexa.flowops.service.node;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 节点管理授权判定：未登录、用户不存在、普通用户一律拒绝，仅超级管理员通过。
 */
class NodeAdminGuardTest {

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final NodeAdminGuard guard = new NodeAdminGuard(userMapper);

    @Test
    void deniesWhenNotLoggedIn() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenThrow(new IllegalStateException("未登录"));

            assertFalse(guard.isSuperAdmin());
        }
    }

    @Test
    void deniesUnknownUser() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("ghost");
            when(userMapper.selectByUsername("ghost")).thenReturn(null);

            assertFalse(guard.isSuperAdmin());
        }
    }

    @Test
    void deniesNormalUser() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("bob");
            when(userMapper.selectByUsername("bob")).thenReturn(user(0));

            assertFalse(guard.isSuperAdmin());
        }
    }

    @Test
    void deniesUserWithNullFlag() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("nobody");
            when(userMapper.selectByUsername("nobody")).thenReturn(user(null));

            assertFalse(guard.isSuperAdmin());
        }
    }

    @Test
    void allowsSuperAdmin() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsString).thenReturn("admin");
            when(userMapper.selectByUsername("admin")).thenReturn(user(1));

            assertTrue(guard.isSuperAdmin());
        }
    }

    private SysUser user(Integer isSuperAdmin) {
        SysUser user = new SysUser();
        user.setUsername("someone");
        user.setIsSuperAdmin(isSuperAdmin);
        return user;
    }
}
