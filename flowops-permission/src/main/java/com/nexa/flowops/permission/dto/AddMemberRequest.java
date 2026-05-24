package com.nexa.flowops.permission.dto;

import lombok.Data;

@Data
public class AddMemberRequest {
    private Long userId;
    private Long roleId;
}
