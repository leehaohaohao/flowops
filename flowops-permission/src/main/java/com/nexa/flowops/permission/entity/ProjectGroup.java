package com.nexa.flowops.permission.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("project_group")
public class ProjectGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    /** 是否为默认项目组（不可删除，未指定组的新用户自动归入） */
    private Integer isDefault;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
