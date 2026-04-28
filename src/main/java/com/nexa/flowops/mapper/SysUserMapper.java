package com.nexa.flowops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexa.flowops.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    SysUser selectByUsername(String username);
}
