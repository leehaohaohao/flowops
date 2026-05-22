package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("perm_definition")
public class PermDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String description;
}
