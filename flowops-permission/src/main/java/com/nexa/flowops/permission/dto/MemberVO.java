package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MemberVO {
    private Long userId;
    private String username;
    private Long roleId;
    private String roleName;
    private LocalDateTime joinTime;
}
