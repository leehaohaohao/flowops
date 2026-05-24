package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class CreateServiceRequest {
    private String name;
    private Long projectId;
    private Integer port;
    private String serviceType;
    private String serviceConfig;
}
