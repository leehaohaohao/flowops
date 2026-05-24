package com.nexa.flowops.service;

import com.nexa.flowops.common.PasswordUtil;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final SysUserMapper userMapper;

    public AuthService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public boolean login(String username, String password) {
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) {
            return false;
        }
        return PasswordUtil.matches(password, user.getPassword());
    }
}
