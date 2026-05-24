package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.util.List;

@Data
public class GrantAccessRequest {
    private Long userId;
    private Long projectId;
    private List<String> permCodes;
}
