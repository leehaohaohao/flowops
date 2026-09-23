package com.nexa.flowops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点登记信息 VO（管理接口用，不暴露 token 本身，仅标记是否已配置）
 */
@Data
public class NexaNodeVO {
    private String runnerId;
    private String nodeName;
    /** online / offline */
    private String status;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createTime;
    /** 是否已配置注册令牌 */
    private boolean hasToken;
}
