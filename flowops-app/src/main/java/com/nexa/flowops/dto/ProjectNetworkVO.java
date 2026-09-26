package com.nexa.flowops.dto;

import lombok.Data;

/**
 * 项目可选网络（已授权且主节点 Docker 实际存在）
 */
@Data
public class ProjectNetworkVO {
    private Long id;
    private String name;
    private String displayName;
    /** MANAGED / IMPORTED */
    private String source;
    /** PRESENT（候选列表只包含实际存在的网络） */
    private String dockerStatus;
}
