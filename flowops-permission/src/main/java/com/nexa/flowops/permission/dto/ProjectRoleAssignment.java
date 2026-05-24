package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class ProjectRoleAssignment {
    private Long projectId;
    private Long roleId;
    /** 额外权限码，与角色权限取并集。如 ["UPLOAD", "DELETE"] */
    private List<String> extraPermissions;
}
