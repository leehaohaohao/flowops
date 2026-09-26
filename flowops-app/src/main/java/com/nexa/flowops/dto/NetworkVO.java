package com.nexa.flowops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主节点网络登记信息（超级管理员视图）
 */
@Data
public class NetworkVO {
    private Long id;
    private String name;
    private String displayName;
    /** MANAGED / IMPORTED */
    private String source;
    /** PRESENT / MISSING（主节点 Docker 实际状态） */
    private String dockerStatus;
    private long grantedProjectCount;
    private long serviceRefCount;
    private LocalDateTime createTime;
}
