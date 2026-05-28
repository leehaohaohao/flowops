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
}
