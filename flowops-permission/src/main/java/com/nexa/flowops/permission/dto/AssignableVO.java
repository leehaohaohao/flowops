package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class AssignableVO {
    private List<RoleVO> roles;
    private List<String> permissions;

    @Data
    public static class RoleVO {
        private Long id;
        private String name;
        private String description;
        private List<String> permissions;
    }
}
