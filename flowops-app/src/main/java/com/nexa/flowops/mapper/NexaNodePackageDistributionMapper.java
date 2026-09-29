package com.nexa.flowops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexa.flowops.entity.NexaNodePackageDistribution;
import org.apache.ibatis.annotations.Mapper;

/** 发布包分发记录（阶段 2 P3） */
@Mapper
public interface NexaNodePackageDistributionMapper extends BaseMapper<NexaNodePackageDistribution> {
}
