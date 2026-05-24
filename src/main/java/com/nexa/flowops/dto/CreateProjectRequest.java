package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class CreateProjectRequest {
    private Long groupId;
    private String name;
    private String description;
}
