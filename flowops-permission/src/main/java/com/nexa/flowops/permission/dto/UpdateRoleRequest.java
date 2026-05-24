package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateRoleRequest {
    private String name;
    private String description;
    private List<String> permissions;
}
