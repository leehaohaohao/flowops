package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("group_member")
public class GroupMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private Long userId;
    private Long roleId;
    /** 成员在角色基础上的额外权限，逗号分隔，如 "UPLOAD,DELETE" */
    private String extraPermissions;
}
