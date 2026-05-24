package com.nexa.flowops.permission.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectDetailVO {
    private Long id;
    private String name;
    private String description;
    private Integer isDefault;
    private Long memberCount;
    private Long serviceCount;
    private LocalDateTime createTime;
}
