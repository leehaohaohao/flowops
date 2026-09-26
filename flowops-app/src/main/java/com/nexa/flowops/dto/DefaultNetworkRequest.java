package com.nexa.flowops.dto;

import lombok.Data;

/**
 * 项目默认网络写入请求；networkId 为 null 表示清除
 */
@Data
public class DefaultNetworkRequest {
    private Long networkId;
}
