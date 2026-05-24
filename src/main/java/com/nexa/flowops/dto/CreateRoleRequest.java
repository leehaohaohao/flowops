package com.nexa.flowops.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateRoleRequest {
    private String name;
    private Long groupId;
    private String description;
    private List<String> permissions;
}
