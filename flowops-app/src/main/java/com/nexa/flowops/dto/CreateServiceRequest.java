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
}
