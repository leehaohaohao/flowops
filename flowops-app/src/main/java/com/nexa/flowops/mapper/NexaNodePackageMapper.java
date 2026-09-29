package com.nexa.flowops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexa.flowops.entity.NexaNodePackage;
import org.apache.ibatis.annotations.Mapper;

/** 发布包索引（阶段 2 P3） */
@Mapper
public interface NexaNodePackageMapper extends BaseMapper<NexaNodePackage> {
}
