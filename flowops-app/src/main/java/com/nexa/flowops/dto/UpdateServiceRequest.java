package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class UpdateServiceRequest {
    private String name;
    private String deployName;
    private String remark;
    private String portMappings;
    private String serviceType;
    private String serviceConfig;
    private String nodeId;
    /** 共享网络ID；null 表示清除共享网络选择（选网络时目标节点必须是本机） */
    private Long networkId;
}
