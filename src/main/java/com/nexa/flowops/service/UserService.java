package com.nexa.flowops.service;

import com.nexa.flowops.entity.SysUser;
import com.nexa.flowops.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    private final SysUserMapper userMapper;

    public UserService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<SysUser> list() {
        return userMapper.selectList(null);
    }

    public void createUser(String username, String password, String role) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(password); // 生产环境应加密
        user.setRole(role);
        userMapper.insert(user);
    }

    public void deleteUser(Long id) {
        userMapper.deleteById(id);
    }
}