package com.nexa.flowops.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("deploy_service")
public class DeployService {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;        // 所属项目 ID
    private String name;           // 服务名称（UI 显示）
    private String deployName;     // 部署名称（Docker Compose 项目名，hostname 规范）
    private String remark;         // 服务备注
    private String portMappings;   // 统一端口映射 JSON: List<PortMapping>
    private String volumeDir;      // 挂载目录
    private String serviceType;    // 服务类型：backend / frontend / fullstack
    private String serviceConfig;  // 结构化配置 JSON
    private String status;         // running, stopped
    private String nodeId;         // 目标执行节点 runnerId；null/空=本机，auto=自动调度
    private Long networkId;        // 共享网络ID；null=不加入共享网络（旧服务为 null）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
