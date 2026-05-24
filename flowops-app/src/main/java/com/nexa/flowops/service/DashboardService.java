package com.nexa.flowops.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final DeployServiceMapper serviceMapper;
    private final DeployRecordMapper recordMapper;
    private final PermissionService permissionService;
    private final SysUserMapper userMapper;

    public DashboardService(DeployServiceMapper serviceMapper,
                            DeployRecordMapper recordMapper,
                            PermissionService permissionService,
                            SysUserMapper userMapper) {
        this.serviceMapper = serviceMapper;
        this.recordMapper = recordMapper;
        this.permissionService = permissionService;
        this.userMapper = userMapper;
    }

    public Map<String, Object> getStats() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<Long> visibleProjectIds = permissionService.getVisibleProjectIds(user.getId());

        Map<String, Object> stats = new HashMap<>();

        // 按可见项目过滤的服务统计
        if (visibleProjectIds.isEmpty()) {
            stats.put("totalServices", 0);
            stats.put("runningServices", 0);
        } else {
            stats.put("totalServices", serviceMapper.selectCount(
                    new LambdaQueryWrapper<DeployService>().in(DeployService::getProjectId, visibleProjectIds)));
            stats.put("runningServices", serviceMapper.selectCount(
                    new LambdaQueryWrapper<DeployService>()
                            .in(DeployService::getProjectId, visibleProjectIds)
                            .eq(DeployService::getStatus, "running")));
        }

        // 部署总次数（全局，不过滤）
        stats.put("totalDeploys", recordMapper.selectCount(null));

        return stats;
    }
}
