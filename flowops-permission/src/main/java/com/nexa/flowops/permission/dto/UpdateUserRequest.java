package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateUserRequest {
    private List<ProjectRoleAssignment> projects;
}
