package com.nexa.flowops.dto;

import lombok.Data;

@Data
public class DashboardStatsVO {
    private Long totalServices;
    private Long runningServices;
    private Long totalDeploys;
}
