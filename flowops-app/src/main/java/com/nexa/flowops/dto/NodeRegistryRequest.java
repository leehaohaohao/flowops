package com.nexa.flowops.dto;

import lombok.Data;

/**
 * 节点登记新增/更新请求（仅超级管理员）。
 * token 为注册令牌原文，服务端自动 sha256 后存储。
 */
@Data
public class NodeRegistryRequest {
    /** 新增时必填；更新时忽略（以路径为准） */
    private String runnerId;
    /** 节点显示名（可选） */
    private String nodeName;
    /** 注册令牌原文；更新时为空表示不修改 */
    private String token;
}
