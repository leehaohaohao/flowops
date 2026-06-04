package com.nexa.flowops.controller;

import com.nexa.flowops.common.base.Result;
import com.nexa.flowops.dto.DashboardStatsVO;
import com.nexa.flowops.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final DashboardService dashboardService;

    @GetMapping("/dashboard")
    public Result<DashboardStatsVO> dashboard() {
        return Result.ok(dashboardService.getStats());
    }
}
