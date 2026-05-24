package com.nexa.flowops.permission.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String role;  // admin, user (legacy, 保留兼容)
    private Integer isSuperAdmin;  // 1=超级管理员, 0=普通用户
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}