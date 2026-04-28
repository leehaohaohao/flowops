package com.nexa.flowops.service;

import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
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
        // 简单密码校验，生产环境应使用 BCrypt
        return user.getPassword().equals(password);
    }
}
