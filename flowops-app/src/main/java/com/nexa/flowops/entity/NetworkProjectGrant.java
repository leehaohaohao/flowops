package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 网络对项目的授权：只有被授权的项目才能让服务加入该共享网络
 */
@Data
@TableName("network_project_grant")
public class NetworkProjectGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long networkId;
    private Long projectId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
