package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateUserRequest {
    private String username;
    private String password;
    /** 项目角色分配列表，为空则自动归入默认项目（viewer 角色） */
    private List<ProjectRoleAssignment> projects;
}
