package com.nexa.flowops.permission.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @JsonIgnore
    private String password;
    private Integer isSuperAdmin;  // 1=超级管理员, 0=普通用户
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}