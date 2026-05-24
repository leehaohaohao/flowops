package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class ProjectBriefVO {
    private Long id;
    private String name;
    private String roleName;
    private List<String> extraPermissions;
}
