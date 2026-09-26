package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目默认网络：仅用于新建服务时预选，已存在服务不随之改变
 */
@Data
@TableName("project_default_network")
public class ProjectDefaultNetwork {
    @TableId(type = IdType.INPUT)
    private Long projectId;
    private Long networkId;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
