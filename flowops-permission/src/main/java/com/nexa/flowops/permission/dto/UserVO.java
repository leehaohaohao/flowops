package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserVO {
    private Long id;
    private String username;
    private boolean isSuperAdmin;
    private LocalDateTime createTime;
    private List<ProjectBriefVO> projects;
}
