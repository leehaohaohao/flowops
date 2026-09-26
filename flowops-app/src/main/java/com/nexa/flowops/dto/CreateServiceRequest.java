package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class CreateServiceRequest {
    private String name;
    private String deployName;
    private String remark;
    private Long projectId;
    private String portMappings;
    private String serviceType;
    private String serviceConfig;
    private String nodeId;
    /** 共享网络ID；null 表示不加入共享网络（选网络时目标节点必须是本机） */
    private Long networkId;
}
