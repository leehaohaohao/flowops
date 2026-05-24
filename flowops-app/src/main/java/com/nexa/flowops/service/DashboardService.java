package com.nexa.flowops.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexa.flowops.dto.DashboardStatsVO;
import com.nexa.flowops.entity.DeployRecord;
import com.nexa.flowops.entity.DeployService;
import com.nexa.flowops.permission.entity.SysUser;
import com.nexa.flowops.mapper.DeployRecordMapper;
import com.nexa.flowops.mapper.DeployServiceMapper;
import com.nexa.flowops.permission.mapper.SysUserMapper;
import com.nexa.flowops.permission.service.PermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public DashboardStatsVO getStats() {
        SysUser user = userMapper.selectByUsername(StpUtil.getLoginIdAsString());
        List<Long> visibleProjectIds = permissionService.getVisibleProjectIds(user.getId());

        DashboardStatsVO stats = new DashboardStatsVO();

        if (visibleProjectIds.isEmpty()) {
            stats.setTotalServices(0L);
            stats.setRunningServices(0L);
        } else {
            stats.setTotalServices(serviceMapper.selectCount(
                    new LambdaQueryWrapper<DeployService>().in(DeployService::getProjectId, visibleProjectIds)));
            stats.setRunningServices(serviceMapper.selectCount(
                    new LambdaQueryWrapper<DeployService>()
                            .in(DeployService::getProjectId, visibleProjectIds)
                            .eq(DeployService::getStatus, "running")));
        }

        stats.setTotalDeploys(recordMapper.selectCount(null));

        return stats;
    }
}
