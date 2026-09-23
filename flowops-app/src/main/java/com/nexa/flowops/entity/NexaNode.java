package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子节点注册表（L1 注册认证）：token 存 sha256(hex)，注册时校验
 */
@Data
@TableName("nexa_node")
public class NexaNode {
    @TableId(type = IdType.INPUT)
    private String runnerId;
    private String nodeName;
    /** 注册令牌 sha256(hex) */
    private String token;
    /** online / offline */
    private String status;
    private LocalDateTime lastHeartbeat;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
