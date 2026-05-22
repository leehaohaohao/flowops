package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("perm_role")
public class PermRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Integer isPreset;  // 1=预设, 0=自定义
    private Long groupId;      // 自定义角色所属项目组，预设角色为 null
    private String description;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
