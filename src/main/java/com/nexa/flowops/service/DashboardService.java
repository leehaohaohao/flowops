package com.nexa.flowops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;

    /**
     * 获取仪表盘统计数据
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();

        // 服务总数
        stats.put("totalServices", serviceMapper.selectCount(null));

        // 运行中的服务数
        stats.put("runningServices", serviceMapper.selectCount(
                new LambdaQueryWrapper<DeployService>().eq(DeployService::getStatus, "running")));

        // 部署总次数
        stats.put("totalDeploys", recordMapper.selectCount(null));

        return stats;
    }
}
