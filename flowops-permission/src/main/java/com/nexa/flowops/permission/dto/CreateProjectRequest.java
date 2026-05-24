package com.nexa.flowops.permission.dto;

import lombok.Data;

@Data
public class CreateProjectRequest {
    private Long groupId;
    private String name;
    private String description;
}
