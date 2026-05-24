package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class UpdateServiceRequest {
    private String name;
    private Integer port;
    private String serviceType;
    private String serviceConfig;
}
